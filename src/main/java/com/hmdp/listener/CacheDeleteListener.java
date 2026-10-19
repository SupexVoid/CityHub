package com.hmdp.listener;

import com.hmdp.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 缓存删除补偿消费者：收到消息后重试删除店铺缓存。
 */
@Component
@org.springframework.context.annotation.Profile("rocketmq")
@Slf4j
@RequiredArgsConstructor
@RocketMQMessageListener(topic = "cache-delete-topic", consumerGroup = "cache-delete-consumer")
public class CacheDeleteListener implements RocketMQListener<String> {

    @Resource
    private com.hmdp.cache.CacheInvalidationService invalidations;

    @Override
    public void onMessage(String shopIdStr) {
        try {
            invalidations.invalidate(Long.valueOf(shopIdStr));
            log.info("缓存删除补偿成功 shopId={}", shopIdStr);
        } catch (Exception e) {
            log.error("缓存删除补偿失败 shopId={}", shopIdStr, e);
            throw new RuntimeException("缓存删除补偿失败", e);
        }
    }
}
