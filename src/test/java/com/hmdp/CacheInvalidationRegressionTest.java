package com.hmdp;
import com.hmdp.cache.CacheInvalidationService;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CacheInvalidationRegressionTest {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    JdbcTemplate db;
    TransactionTemplate tx;
    CacheClient cache;
    CacheInvalidationService invalidations;
    @BeforeEach void setup(){
        factory=new LettuceConnectionFactory("127.0.0.1",Integer.getInteger("test.redis.port",16379));factory.afterPropertiesSet();redis=new StringRedisTemplate(factory);
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE tb_cache_invalidation(shop_id BIGINT PRIMARY KEY,token VARCHAR(36))");
        cache=new CacheClient(redis);
        invalidations=new CacheInvalidationService(db,cache,new DefaultListableBeanFactory().getBeanProvider(RocketMQTemplate.class));
    }
    @AfterEach void close(){cache.close();factory.destroy();}
    @Test void rolledBackUpdateDoesNotDeleteOldCacheOrLeaveTask(){
        redis.opsForValue().set("cache:shop:901","old");
        tx.execute(status->{invalidations.afterShopUpdate(901);status.setRollbackOnly();return null;});
        assertEquals("old",redis.opsForValue().get("cache:shop:901"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM tb_cache_invalidation",Integer.class));
    }
    @Test void cacheIsInvalidatedOnlyAfterCommit(){
        redis.opsForValue().set("cache:shop:902","old");
        tx.execute(status->{invalidations.afterShopUpdate(902);assertEquals("old",redis.opsForValue().get("cache:shop:902"));return null;});
        assertNull(redis.opsForValue().get("cache:shop:902"));
        invalidations.retry();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM tb_cache_invalidation",Integer.class));
    }
    @Test void failedRedisDeletionRetainsRecoverableTask(){
        CacheClient broken=new CacheClient(redis){@Override public void invalidate(String key){throw new IllegalStateException("simulated Redis outage");}};
        CacheInvalidationService failing=new CacheInvalidationService(db,broken,new DefaultListableBeanFactory().getBeanProvider(RocketMQTemplate.class));
        try{
            redis.opsForValue().set("cache:shop:903","old");
            tx.execute(status->{failing.afterShopUpdate(903);return null;});
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM tb_cache_invalidation",Integer.class));
            invalidations.retry();
            assertNull(redis.opsForValue().get("cache:shop:903"));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM tb_cache_invalidation",Integer.class));
        }finally{broken.close();}
    }
}
