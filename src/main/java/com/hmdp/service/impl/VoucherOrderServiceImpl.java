package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.order.*;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.time.LocalDateTime;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper,VoucherOrder> implements IVoucherOrderService {
    @Resource private RedisReservationStore reservations;
    @Resource private OrderMessageGateway messages;
    @Resource private OrderLifecycleService lifecycle;
    @Resource private RedisIdWorker ids;

    @Override public Result seckillVoucher(Long voucherId) {
        if(UserHolder.getUser()==null) return Result.fail("请先登录");
        VoucherOrder order=new VoucherOrder().setId(ids.nextId("order")).setUserId(UserHolder.getUser().getId())
                .setVoucherId(voucherId).setCreateTime(LocalDateTime.now());
        int result=reservations.reserve(order);
        if(result!=0) {
            switch(result){
                case 1:return Result.fail("库存不足");
                case 2:return Result.fail("不能重复下单");
                case 3:return Result.fail("不在活动时间内");
                default:return Result.fail("活动尚未初始化");
            }
        }
        try{messages.publish(order);}catch(Exception e){log.warn("Publish deferred to recovery order={}",order.getId(),e);}
        // Reservation + recoverable task exists. This is acceptance, not a persisted order guarantee.
        return Result.ok(order.getId());
    }
    @Override public void createVoucherOrder(VoucherOrder order){lifecycle.persist(order);}
    @Override public void handleVoucherOrder(VoucherOrder order){lifecycle.persist(order);}
    @Override public boolean payCallback(Long id){
        return UserHolder.getUser()!=null && lifecycle.pay(id,UserHolder.getUser().getId());
    }
    @Override public boolean closeTimeoutOrder(Long id){return lifecycle.closeExpired(id);}
}
