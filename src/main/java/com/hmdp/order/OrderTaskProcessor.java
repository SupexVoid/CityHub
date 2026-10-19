package com.hmdp.order;
import com.hmdp.entity.VoucherOrder;
import org.springframework.stereotype.Service;

@Service
public class OrderTaskProcessor {
    private final OrderLifecycleService lifecycle;
    private final RedisReservationStore reservations;
    public OrderTaskProcessor(OrderLifecycleService lifecycle, RedisReservationStore reservations) {
        this.lifecycle=lifecycle; this.reservations=reservations;
    }
    public void process(VoucherOrder order) {
        lifecycle.persist(order);
        reservations.complete(order.getId());
        lifecycle.closeExpired(order.getId());
    }
}
