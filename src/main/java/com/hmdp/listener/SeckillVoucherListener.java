package com.hmdp.listener;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.OrderTaskProcessor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.*;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
@Profile("rocketmq")
@RocketMQMessageListener(topic="seckill-order-topic",consumerGroup="seckill-order-consumer")
public class SeckillVoucherListener implements RocketMQListener<String> {
    private final OrderTaskProcessor processor;
    private final com.hmdp.order.DelayedOrderChecks checks;
    public SeckillVoucherListener(OrderTaskProcessor processor,com.hmdp.order.DelayedOrderChecks checks){this.processor=processor;this.checks=checks;}
    @Override public void onMessage(String message){
        VoucherOrder order=JSONUtil.toBean(message,VoucherOrder.class);
        processor.process(order);
        checks.checkOrSchedule(order.getId());
    }
}
