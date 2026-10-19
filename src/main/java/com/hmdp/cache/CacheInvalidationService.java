package com.hmdp.cache;

import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import java.util.*;

/** Invalidation intent is committed in the same MySQL transaction as the shop update. */
@Slf4j
@Service
public class CacheInvalidationService {
    private final JdbcTemplate db;
    private final CacheClient cache;
    private final ObjectProvider<RocketMQTemplate> mq;
    public CacheInvalidationService(JdbcTemplate db,CacheClient cache,ObjectProvider<RocketMQTemplate> mq){
        this.db=db;this.cache=cache;this.mq=mq;
    }
    public void afterShopUpdate(long shopId){
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Shop invalidation must be recorded inside transaction");
        String token=UUID.randomUUID().toString();
        db.update("INSERT INTO tb_cache_invalidation(shop_id,token) VALUES(?,?) ON DUPLICATE KEY UPDATE token=?",shopId,token,token);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){
                // Do not write DB cleanup through the original transaction's afterCommit connection.
                // The scheduler (or MQ consumer) acknowledges the durable intent independently.
                try{cache.invalidate(RedisConstants.CACHE_SHOP_KEY+shopId);}catch(Exception e){
                    log.warn("Cache invalidation retained in DB shop={}",shopId,e);
                    RocketMQTemplate template=mq.getIfAvailable();
                    if(template!=null) try{template.convertAndSend("cache-delete-topic",Long.toString(shopId));}
                    catch(Exception sendError){log.warn("Cache MQ send deferred to scan shop={}",shopId,sendError);}
                }
            }
        });
    }
    public void invalidate(long shopId){
        List<String> tokens=db.queryForList("SELECT token FROM tb_cache_invalidation WHERE shop_id=?",String.class,shopId);
        cache.invalidate(RedisConstants.CACHE_SHOP_KEY+shopId);
        if(!tokens.isEmpty()) db.update("DELETE FROM tb_cache_invalidation WHERE shop_id=? AND token=?",shopId,tokens.get(0));
    }
    @Scheduled(fixedDelayString="${cityseek.cache.retry-delay-ms:5000}")
    public void retry(){
        for(Long id:db.queryForList("SELECT shop_id FROM tb_cache_invalidation ORDER BY shop_id LIMIT 100",Long.class)){
            try{invalidate(id);}catch(Exception e){log.warn("Cache invalidate retry pending shop={}",id,e);}
        }
    }
}
