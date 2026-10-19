package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real Redis on a dedicated test port; never connects to the user's default instance. */
class CacheRegressionTest {
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    CacheClient cache;
    String prefix;
    @BeforeAll static void connect() {
        connection = new LettuceConnectionFactory("127.0.0.1", Integer.getInteger("test.redis.port", 16379));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
    }
    @AfterAll static void disconnect() { connection.destroy(); }
    @BeforeEach void setup() { cache = new CacheClient(redis); prefix = "test:cache:" + UUID.randomUUID() + ":"; }
    @AfterEach void shutdown() { cache.close(); }

    @Test void missingCacheLoadsExistingShopAndCachesIt() {
        AtomicInteger reads = new AtomicInteger();
        Shop shop = cache.queryWithLogicalExpire(prefix, 1L, Shop.class,
                id -> { reads.incrementAndGet(); return new Shop().setId(id).setName("original"); }, 30L, TimeUnit.SECONDS);
        assertNotNull(shop, "a missing cache must not mean a missing database row");
        cache.queryWithLogicalExpire(prefix, 1L, Shop.class, id -> { fail("should hit Redis"); return null; }, 30L, TimeUnit.SECONDS);
        assertEquals(1, reads.get());
        assertTrue(redis.getExpire(prefix + 1) > 30, "physical TTL must outlive logical expiry");
    }
    @Test void missingShopUsesNegativeCache() {
        AtomicInteger reads = new AtomicInteger();
        for (int i = 0; i < 2; i++) assertNull(cache.queryWithLogicalExpire(prefix, 8L, Shop.class,
                id -> { reads.incrementAndGet(); return null; }, 30L, TimeUnit.SECONDS));
        assertEquals(1, reads.get(), "negative lookups should not repeatedly query DB");
    }
    @Test void expiredCacheReturnsOldValueThenKeepsRebuiltValue() throws Exception {
        cache.setWithLogicalExpire(prefix + 1, new Shop().setId(1L).setName("old"), -1L, TimeUnit.SECONDS);
        assertEquals("old", cache.queryWithLogicalExpire(prefix, 1L, Shop.class,
                id -> new Shop().setId(id).setName("new"), 30L, TimeUnit.SECONDS).getName());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until && !String.valueOf(redis.opsForValue().get(prefix + 1)).contains("new")) Thread.sleep(10);
        assertNotNull(redis.opsForValue().get(prefix + 1), "refresh must not delete data as if it were the lock");
        assertTrue(redis.opsForValue().get(prefix + 1).contains("new"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("lock:rebuild:" + prefix + 1)));
    }
    @Test void localHitAvoidsRedisAndInvalidationReloadsDatabase() {
        AtomicInteger reads=new AtomicInteger();
        java.util.function.Function<Long,Shop> loader=id->new Shop().setId(id).setName("v"+reads.incrementAndGet());
        cache.queryWithLogicalExpire(prefix,1L,Shop.class,loader,30L,TimeUnit.SECONDS);
        cache.queryWithLogicalExpire(prefix,1L,Shop.class,loader,30L,TimeUnit.SECONDS);
        redis.delete(prefix+1); // proves the next read uses the bounded local snapshot
        assertEquals("v1",cache.queryWithLogicalExpire(prefix,1L,Shop.class,loader,30L,TimeUnit.SECONDS).getName());
        assertTrue(cache.localHitCount()>0);
        cache.invalidate(prefix+1);
        assertEquals("v2",cache.queryWithLogicalExpire(prefix,1L,Shop.class,loader,30L,TimeUnit.SECONDS).getName());
    }
    @Test void invalidationDuringDatabaseLoadPreventsStaleRefill() {
        cache.queryWithLogicalExpire(prefix,1L,Shop.class,id->{
            cache.invalidate(prefix+1);
            return new Shop().setId(id).setName("stale-read");
        },30L,TimeUnit.SECONDS);
        assertNull(redis.opsForValue().get(prefix+1),"an older load must not undo an invalidation");
    }
    @Test void delayedLocalFillCannotResurrectValueAfterInvalidation() throws Exception {
        String key=prefix+1;
        cache.setWithLogicalExpire(key,new Shop().setId(1L).setName("old"),30L,TimeUnit.SECONDS);
        org.springframework.data.redis.core.ValueOperations<String,String> ops=org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        StringRedisTemplate instrumented=org.mockito.Mockito.spy(redis);
        org.mockito.Mockito.doReturn(ops).when(instrumented).opsForValue();
        java.util.concurrent.CountDownLatch fetched=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch resume=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first=new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.when(ops.get(key)).thenAnswer(invocation->{
            String value=redis.opsForValue().get(key);
            if(first.getAndSet(false)){fetched.countDown();assertTrue(resume.await(3,TimeUnit.SECONDS));}
            return value;
        });
        // All other operations remain real Redis operations; only pause the GET after its result.
        org.mockito.Mockito.when(ops.get("cache:version:"+key)).thenAnswer(i->redis.opsForValue().get("cache:version:"+key));
        cache.close();cache=new CacheClient(instrumented);
        java.util.concurrent.ExecutorService threads=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            java.util.concurrent.Future<?> reader=threads.submit(()->cache.queryWithLogicalExpire(prefix,1L,Shop.class,id->new Shop().setId(id).setName("new"),30L,TimeUnit.SECONDS));
            assertTrue(fetched.await(3,TimeUnit.SECONDS));
            java.util.concurrent.Future<?> invalidation=threads.submit(()->cache.invalidate(key));
            try{invalidation.get(150,TimeUnit.MILLISECONDS);}catch(java.util.concurrent.TimeoutException expected){}
            resume.countDown();reader.get(3,TimeUnit.SECONDS);invalidation.get(3,TimeUnit.SECONDS);
            org.mockito.Mockito.when(ops.setIfAbsent(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(TimeUnit.class)))
                    .thenAnswer(i->redis.opsForValue().setIfAbsent(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3)));
            assertEquals("new",cache.queryWithLogicalExpire(prefix,1L,Shop.class,id->new Shop().setId(id).setName("new"),30L,TimeUnit.SECONDS).getName());
        }finally{resume.countDown();threads.shutdownNow();}
    }
}
