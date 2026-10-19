package com.hmdp.order;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
@Component
@Profile("rocketmq")
public class RocketOrderGateway implements OrderMessageGateway {
    private final RocketMQTemplate mq;
    public RocketOrderGateway(RocketMQTemplate mq){this.mq=mq;}
    public void publish(VoucherOrder order){mq.syncSend("seckill-order-topic",JSONUtil.toJsonStr(order));}
}
