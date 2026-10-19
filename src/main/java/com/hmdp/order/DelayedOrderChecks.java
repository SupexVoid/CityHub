package com.hmdp.order;
import com.hmdp.entity.VoucherOrder;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
@Profile("rocketmq")
public class DelayedOrderChecks {
    private final OrderLifecycleService lifecycle;
    private final RocketMQTemplate mq;
    private final long timeout;
    private static final int[] SECONDS={1,5,10,30,60,120,180,240,300,360,420,480,540,600,1200,1800,3600,7200};
    public DelayedOrderChecks(OrderLifecycleService lifecycle,RocketMQTemplate mq,
            @Value("${cityseek.order.timeout-seconds:900}") long timeout){this.lifecycle=lifecycle;this.mq=mq;this.timeout=timeout;}
    public static int levelFor(long remaining){
        int level=1;
        for(int i=0;i<SECONDS.length;i++) if(SECONDS[i]<=remaining) level=i+1;
        return level;
    }
    public void checkOrSchedule(long orderId){
        VoucherOrder order=lifecycle.find(orderId);
        if(order==null || order.getStatus()!=1) return;
        long millis=Duration.between(LocalDateTime.now(),order.getCreateTime().plusSeconds(timeout)).toMillis();
        if(millis<=0){lifecycle.closeExpired(orderId);return;}
        long remaining=Math.max(1,(millis+999)/1000);
        mq.syncSend("order-timeout-topic",MessageBuilder.withPayload(Long.toString(orderId)).build(),3000,levelFor(remaining));
    }
}
