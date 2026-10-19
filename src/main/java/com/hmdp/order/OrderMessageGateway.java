package com.hmdp.order;
import com.hmdp.entity.VoucherOrder;
public interface OrderMessageGateway { void publish(VoucherOrder order); }
