package com.hmdp.controller;


import com.hmdp.annotation.RateLimiter;
import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;
    @RateLimiter(key = "seckill", windowSeconds = 1, count = 5, dimension = "user")
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }


    @org.springframework.web.bind.annotation.GetMapping("/{orderId}")
    public Result findOrder(@PathVariable("orderId") Long orderId) {
        com.hmdp.entity.VoucherOrder order = voucherOrderService.getById(orderId);
        if (order == null) return Result.fail("订单处理中或不存在，请稍后查询");
        if (com.hmdp.utils.UserHolder.getUser() == null || !order.getUserId().equals(com.hmdp.utils.UserHolder.getUser().getId()))
            return Result.fail("无权查看该订单");
        return Result.ok(order);
    }
}
