package com.hmdp.order;
import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Slf4j
@Component
public class OrderRecoveryJob {
    private final RedisReservationStore reservations;
    private final OrderMessageGateway messages;
    private final OrderLifecycleService lifecycle;
    public OrderRecoveryJob(RedisReservationStore reservations,OrderMessageGateway messages,OrderLifecycleService lifecycle){
        this.reservations=reservations;this.messages=messages;this.lifecycle=lifecycle;
    }
    @Scheduled(fixedDelayString="${cityseek.order.retry-delay-ms:5000}")
    public void retryPending(){
        for(VoucherOrder order:reservations.pending(100)) {
            try{messages.publish(order);}catch(Exception e){log.warn("Task publish pending order={}",order.getId(),e);}
        }
    }
    @Scheduled(fixedDelayString="${cityseek.order.scan-delay-ms:60000}")
    public void closeExpired(){lifecycle.closeBatch();}
}
