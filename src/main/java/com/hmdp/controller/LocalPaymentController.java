package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.OrderLifecycleService;
import com.hmdp.utils.UserHolder;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** Authenticated local simulation only. Does not charge or refund money. */
@RestController
@Profile("local")
@RequestMapping("/voucher-order/pay")
public class LocalPaymentController {
    private final OrderLifecycleService lifecycle;
    public LocalPaymentController(OrderLifecycleService lifecycle){this.lifecycle=lifecycle;}
    @PostMapping("/mock/{id}")
    public Result pay(@PathVariable Long id){
        if(UserHolder.getUser()==null) return Result.fail("请先登录");
        return lifecycle.pay(id,UserHolder.getUser().getId())?Result.ok():Result.fail("订单不属于当前用户、已到期或已取消");
    }
    @PostMapping("/mock-late/{id}")
    public Result late(@PathVariable Long id){
        VoucherOrder order=lifecycle.find(id);
        if(order==null || UserHolder.getUser()==null || !order.getUserId().equals(UserHolder.getUser().getId())) return Result.fail("订单不存在或无权操作");
        if(order.getStatus()!=4 && order.getStatus()!=5) return Result.fail("仅模拟已取消订单的晚到支付");
        lifecycle.recordLatePayment(id);
        return Result.ok("仅记录本地退款待办，不会执行真实退款");
    }
}
