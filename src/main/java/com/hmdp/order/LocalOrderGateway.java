package com.hmdp.order;

import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import javax.annotation.PreDestroy;
import java.util.concurrent.*;

/** Local transport rehearsal, not a claim to run a RocketMQ broker. Redis retains retry tasks. */
@Slf4j
@Component
@Profile("local")
public class LocalOrderGateway implements OrderMessageGateway {
    private final OrderTaskProcessor processor;
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),
            r->{Thread t=new Thread(r,"local-orders");t.setDaemon(true);return t;});
    public LocalOrderGateway(OrderTaskProcessor processor) { this.processor=processor; }
    public void publish(VoucherOrder order) {
        worker.submit(()->{try{processor.process(order);}catch(Exception e){log.error("Local task retained for retry order={}",order.getId(),e);}});
    }
    @PreDestroy public void close(){worker.shutdownNow();}
}
