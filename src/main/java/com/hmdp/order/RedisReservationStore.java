package com.hmdp.order;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class RedisReservationStore {
    public static final String PENDING = "seckill:pending";
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> RESERVE = script("seckill.lua");
    private static final DefaultRedisScript<Long> RELEASE = script("release_reservation.lua");
    private static DefaultRedisScript<Long> script(String file) {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource(file)); s.setResultType(Long.class); return s;
    }
    public RedisReservationStore(StringRedisTemplate redis) { this.redis = redis; }
    private List<String> keys(Long voucher) { return Arrays.asList("seckill:stock:"+voucher,
            "seckill:order:"+voucher, "seckill:owners:"+voucher, PENDING, "seckill:activity:"+voucher); }
    public int reserve(VoucherOrder order) {
        Long result = redis.execute(RESERVE, keys(order.getVoucherId()), order.getUserId().toString(),
                order.getId().toString(), Long.toString(System.currentTimeMillis()), JSONUtil.toJsonStr(order));
        if (result == null) throw new IllegalStateException("Redis reservation returned no result");
        return result.intValue();
    }
    public void release(VoucherOrder order) {
        Long result = redis.execute(RELEASE, keys(order.getVoucherId()).subList(0,4),
                order.getUserId().toString(), order.getId().toString());
        if (result == null || result < 0) throw new IllegalStateException("Redis reservation requires reconciliation");
    }
    public void complete(Long orderId) { redis.opsForHash().delete(PENDING, orderId.toString()); }
    public List<VoucherOrder> pending(int limit) {
        List<VoucherOrder> tasks = new ArrayList<>();
        try (Cursor<Map.Entry<Object,Object>> cursor = redis.opsForHash().scan(PENDING, ScanOptions.scanOptions().count(limit).build())) {
            while (cursor.hasNext() && tasks.size() < limit) {
                tasks.add(JSONUtil.toBean(cursor.next().getValue().toString(), VoucherOrder.class));
            }
        }
        return tasks;
    }
    /** For newly created activities only. Never reset existing stock on application startup. */
    public void initialize(Long voucher, int stock, LocalDateTime begin, LocalDateTime end) {
        redis.opsForValue().set("seckill:stock:"+voucher, String.valueOf(stock));
        Map<String,String> times = new HashMap<>();
        times.put("begin", Long.toString(begin.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        times.put("end", Long.toString(end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        redis.opsForHash().putAll("seckill:activity:"+voucher, times);
    }
}
