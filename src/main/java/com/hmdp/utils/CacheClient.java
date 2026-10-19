package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Display data only. Redis stock/eligibility must never use this local cache. */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate redis;
    private final Object[] localGuards = new Object[64];
    private final Cache<String, String> local = Caffeine.newBuilder().maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.SECONDS).recordStats().build();
    private final ExecutorService rebuild = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), r -> { Thread t = new Thread(r, "cache-rebuild"); t.setDaemon(true); return t; });
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) end return 0", Long.class);
    private static final DefaultRedisScript<Long> WRITE = new DefaultRedisScript<>(
            "if (redis.call('get',KEYS[2]) or '0')~=ARGV[1] then return 0 end " +
            "redis.call('set',KEYS[1],ARGV[2],'EX',ARGV[3]); return 1", Long.class);
    private static final DefaultRedisScript<Long> EVICT = new DefaultRedisScript<>(
            "redis.call('incr',KEYS[2]); redis.call('expire',KEYS[2],86400); return redis.call('del',KEYS[1])", Long.class);

    public CacheClient(StringRedisTemplate redis) {
        this.redis = redis;
        for (int i=0;i<localGuards.length;i++) localGuards[i]=new Object();
    }
    private Object guard(String key) { return localGuards[(key.hashCode() & Integer.MAX_VALUE) % localGuards.length]; }
    public void set(String key, Object value, Long time, TimeUnit unit) {
        redis.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
        local.invalidate(key);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        redis.opsForValue().set(key, envelope(value, time, unit), physicalTtl(time, unit), TimeUnit.SECONDS);
        local.invalidate(key);
    }
    private String envelope(Object value, long time, TimeUnit unit) {
        RedisData data = new RedisData(); data.setData(value);
        data.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        return JSONUtil.toJsonStr(data);
    }
    private long physicalTtl(long time, TimeUnit unit) { return Math.max(60, unit.toSeconds(time) * 3); }
    private String version(String key) {
        String value = redis.opsForValue().get("cache:version:" + key);
        return value == null ? "0" : value;
    }
    public void invalidate(String key) {
        synchronized (guard(key)) {
            local.invalidate(key);
            redis.execute(EVICT, Arrays.asList(key, "cache:version:" + key));
        }
    }
    public void invalidateLocal(String key) { local.invalidate(key); }
    public long localHitCount() { return local.stats().hitCount(); }

    public <R, ID> R queryWithPassThrough(String prefix, ID id, Class<R> type,
            Function<ID, R> loader, Long time, TimeUnit unit) {
        String key = prefix + id;
        String json = redis.opsForValue().get(key);
        if (json != null) return json.isEmpty() ? null : JSONUtil.toBean(json, type);
        String generation = version(key);
        R value = loader.apply(id);
        redis.execute(WRITE, Arrays.asList(key, "cache:version:" + key), generation,
                value == null ? "" : JSONUtil.toJsonStr(value),
                String.valueOf(value == null ? 120 : Math.max(1, unit.toSeconds(time))));
        return value;
    }
    public <R, ID> R queryWithLogicalExpire(String prefix, ID id, Class<R> type,
            Function<ID, R> loader, Long time, TimeUnit unit) {
        String key = prefix + id;
        String json;
        synchronized (guard(key)) {
            json = local.getIfPresent(key);
            if (json == null) { json = redis.opsForValue().get(key); if (json != null) local.put(key, json); }
        }
        if (json == null) return loadMissing(key, id, type, loader, time, unit);
        if (json.isEmpty()) return null;
        RedisData data = JSONUtil.toBean(json, RedisData.class);
        // Old plain JSON cache entries are reloaded rather than silently deserialized as empty data.
        if (data.getExpireTime() == null) { invalidate(key); return loadMissing(key, id, type, loader, time, unit); }
        R value = data.getData() == null ? null : JSONUtil.toBean(JSONUtil.toJsonStr(data.getData()), type);
        if (!data.getExpireTime().isAfter(LocalDateTime.now())) {
            String token = UUID.randomUUID().toString();
            String lock = "lock:rebuild:" + key;
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, token, 10, TimeUnit.SECONDS))) {
                try {
                    rebuild.submit(() -> {
                        try { loadAndWrite(key, id, loader, time, unit); }
                        catch (Exception e) { log.warn("Cache rebuild failed key={}", key, e); }
                        finally { release(lock, token); }
                    });
                } catch (RejectedExecutionException e) { release(lock, token); log.warn("Rebuild queue full key={}", key); }
            }
        }
        return value;
    }
    private <R, ID> R loadMissing(String key, ID id, Class<R> type, Function<ID, R> loader, Long time, TimeUnit unit) {
        String lock = "lock:rebuild:" + key;
        for (int attempt = 0; attempt < 40; attempt++) {
            String token = UUID.randomUUID().toString();
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, token, 10, TimeUnit.SECONDS))) {
                try {
                    String json = redis.opsForValue().get(key);
                    if (json != null) {
                        if (json.isEmpty()) return null;
                        RedisData data = JSONUtil.toBean(json, RedisData.class);
                        return data.getData() == null ? null : JSONUtil.toBean(JSONUtil.toJsonStr(data.getData()), type);
                    }
                    return loadAndWrite(key, id, loader, time, unit);
                } finally { release(lock, token); }
            }
            try { Thread.sleep(25); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("Cache lookup interrupted", e);
            }
        }
        throw new IllegalStateException("Cache rebuild busy; retry shortly");
    }
    private <R, ID> R loadAndWrite(String key, ID id, Function<ID, R> loader, Long time, TimeUnit unit) {
        String generation = version(key);
        R value = loader.apply(id);
        redis.execute(WRITE, Arrays.asList(key, "cache:version:" + key), generation,
                value == null ? "" : envelope(value, time, unit),
                String.valueOf(value == null ? 120 : physicalTtl(time, unit)));
        local.invalidate(key);
        return value;
    }
    private void release(String lock, String token) { redis.execute(UNLOCK, Collections.singletonList(lock), token); }
    @PreDestroy public void close() { rebuild.shutdownNow(); local.invalidateAll(); }
}
