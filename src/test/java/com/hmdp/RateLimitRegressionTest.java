package com.hmdp;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RateLimitRegressionTest {
    @Test void simultaneousRequestsHaveIndependentMembersAndExpireOutOfWindow(){
        LettuceConnectionFactory factory=new LettuceConnectionFactory("127.0.0.1",Integer.getInteger("test.redis.port",16379));
        factory.afterPropertiesSet();
        try{
            StringRedisTemplate redis=new StringRedisTemplate(factory);
            DefaultRedisScript<Long> script=new DefaultRedisScript<>(); script.setLocation(new ClassPathResource("sliding_window.lua"));script.setResultType(Long.class);
            String key="test:limit:"+UUID.randomUUID(); long now=System.currentTimeMillis();
            for(int i=0;i<5;i++) assertEquals(1L,redis.execute(script,Collections.singletonList(key),"10","5",Long.toString(now),UUID.randomUUID().toString()));
            assertEquals(0L,redis.execute(script,Collections.singletonList(key),"10","5",Long.toString(now),UUID.randomUUID().toString()));
            assertEquals(5L,redis.opsForZSet().size(key));
            assertEquals(1L,redis.execute(script,Collections.singletonList(key),"10","5",Long.toString(now+10001),UUID.randomUUID().toString()));
            assertEquals(1L,redis.opsForZSet().size(key));
        }finally{factory.destroy();}
    }
}
