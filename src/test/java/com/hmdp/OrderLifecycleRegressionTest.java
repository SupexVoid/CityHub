package com.hmdp;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.OrderLifecycleService;
import com.hmdp.order.RedisReservationStore;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderLifecycleRegressionTest {
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    JdbcTemplate db;
    RedisReservationStore store;
    OrderLifecycleService service;
    long voucher;
    @BeforeAll static void connect() {
        connection = new LettuceConnectionFactory("127.0.0.1", Integer.getInteger("test.redis.port", 16379));
        connection.afterPropertiesSet(); redis = new StringRedisTemplate(connection);
    }
    @AfterAll static void disconnect() { connection.destroy(); }
    @BeforeEach void setup() {
        redis.delete(RedisReservationStore.PENDING); // dedicated test Redis, stable fixture order IDs
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE tb_seckill_voucher(voucher_id BIGINT PRIMARY KEY, stock INT)");
        db.execute("CREATE TABLE tb_voucher_order(id BIGINT PRIMARY KEY, user_id BIGINT, voucher_id BIGINT, status INT DEFAULT 1, create_time TIMESTAMP, pay_time TIMESTAMP, update_time TIMESTAMP, redis_released INT DEFAULT 0)");
        db.execute("CREATE TABLE tb_refund_task(order_id BIGINT PRIMARY KEY, reason VARCHAR(255), create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        voucher = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1000000000L);
        db.update("INSERT INTO tb_seckill_voucher VALUES(?,2)", voucher);
        store = new RedisReservationStore(redis);
        service = new OrderLifecycleService(db, new TransactionTemplate(new DataSourceTransactionManager(ds)), store, 900);
        store.initialize(voucher, 2, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
    }
    VoucherOrder order(long id, long user, boolean expired) {
        return new VoucherOrder().setId(id).setUserId(user).setVoucherId(voucher)
                .setCreateTime(LocalDateTime.now().minusSeconds(expired ? 901 : 0));
    }
    @Test void repeatDeliveryDoesNotDeductStockTwice() {
        VoucherOrder o = order(10, 1, false);
        assertEquals(0, store.reserve(o)); service.persist(o); service.persist(o);
        assertEquals(1, db.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucher));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
    }
    @Test void failedStockDeductionRollsBackOrderInsert() {
        db.update("UPDATE tb_seckill_voucher SET stock=0");
        assertThrows(IllegalStateException.class, () -> service.persist(order(11, 1, false)));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
    }
    @Test void pendingTaskSurvivesConsumerFailureAndIsRemovedOnlyAfterSuccessfulCommit(){
        com.hmdp.order.OrderTaskProcessor processor=new com.hmdp.order.OrderTaskProcessor(service,store);
        VoucherOrder o=order(88,2,false);store.reserve(o);
        db.update("UPDATE tb_seckill_voucher SET stock=0");
        assertThrows(IllegalStateException.class,()->processor.process(o));
        assertTrue(redis.opsForHash().hasKey(RedisReservationStore.PENDING,"88"));
        db.update("UPDATE tb_seckill_voucher SET stock=2");
        processor.process(o);processor.process(o);
        assertFalse(redis.opsForHash().hasKey(RedisReservationStore.PENDING,"88"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order",Integer.class));
    }
    @Test void earlyOrPaidOrderCannotBeClosed() {
        VoucherOrder o = order(12, 1, false); store.reserve(o); service.persist(o);
        assertFalse(service.closeExpired(12));
        assertFalse(service.pay(12, 999));
        assertTrue(service.pay(12, 1)); assertTrue(service.pay(12, 1));
        assertFalse(service.closeExpired(12));
    }
    @Test void cancelReturnsBothStocksOnceAndOldReleaseCannotStealNewReservation() {
        VoucherOrder old = order(13, 1, true); store.reserve(old); service.persist(old);
        assertTrue(service.closeExpired(13)); assertFalse(service.closeExpired(13));
        service.retryReleases(); service.retryReleases();
        assertEquals("2", redis.opsForValue().get("seckill:stock:" + voucher));
        assertEquals(2, db.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucher));
        VoucherOrder next = order(14, 1, false); assertEquals(0, store.reserve(next));
        store.release(old);
        assertEquals("1", redis.opsForValue().get("seckill:stock:" + voucher));
        assertEquals("14", redis.opsForHash().get("seckill:owners:" + voucher, "1"));
        assertFalse(service.pay(13, 1)); // expired payment is not revived
    }
    @Test void concurrentRequestsCannotOversellOrReserveOneUserTwice() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 30; i++) { final int n=i; results.add(pool.submit(() -> store.reserve(order(100+n, n%4, false)))); }
            int successes=0; for (Future<Integer> f : results) if (f.get()==0) successes++;
            assertEquals(2, successes);
            assertEquals("0", redis.opsForValue().get("seckill:stock:"+voucher));
            assertEquals(2L, redis.opsForSet().size("seckill:order:"+voucher));
        } finally { pool.shutdownNow(); }
    }
    @Test void activityWindowIsValidatedBeforeReservation() {
        store.initialize(voucher, 2, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2));
        assertEquals(3, store.reserve(order(30, 1, false)));
        assertEquals("2", redis.opsForValue().get("seckill:stock:"+voucher));
    }
    @Test void redisReleaseFailureIsRetriedFromDatabaseWithoutAddingMysqlStockAgain() {
        RedisReservationStore failing=new RedisReservationStore(redis){
            boolean fail=true;
            @Override public void release(VoucherOrder o){if(fail){fail=false;throw new IllegalStateException("simulated Redis outage");}super.release(o);}
        };
        DriverManagerDataSource ds=(DriverManagerDataSource)db.getDataSource();
        OrderLifecycleService withFailure=new OrderLifecycleService(db,new TransactionTemplate(new DataSourceTransactionManager(ds)),failing,900);
        VoucherOrder o=order(40,1,true);store.reserve(o);withFailure.persist(o);
        assertTrue(withFailure.closeExpired(40));
        assertEquals(0,db.queryForObject("SELECT redis_released FROM tb_voucher_order WHERE id=40",Integer.class));
        withFailure.retryReleases(); withFailure.retryReleases();
        assertEquals("2",redis.opsForValue().get("seckill:stock:"+voucher));
        assertEquals(2,db.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?",Integer.class,voucher));
        assertEquals(1,db.queryForObject("SELECT redis_released FROM tb_voucher_order WHERE id=40",Integer.class));
    }
    @Test void repeatedLatePaymentRecordsOneRefundTaskAndDoesNotReleaseTwice(){
        VoucherOrder o=order(41,1,true);store.reserve(o);service.persist(o);service.closeExpired(41);
        service.recordLatePayment(41);service.recordLatePayment(41);service.retryReleases();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM tb_refund_task",Integer.class));
        assertEquals(5,service.find(41).getStatus());
        assertEquals("2",redis.opsForValue().get("seckill:stock:"+voucher));
    }
}
