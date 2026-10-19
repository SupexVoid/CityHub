package com.hmdp.order;

import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/** The local and RocketMQ consumers use exactly the same DB transaction boundaries. */
@Slf4j
@Service
public class OrderLifecycleService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RedisReservationStore reservations;
    private final long timeoutSeconds;
    public OrderLifecycleService(JdbcTemplate db, TransactionTemplate tx, RedisReservationStore reservations,
            @Value("${cityseek.order.timeout-seconds:900}") long timeoutSeconds) {
        this.db=db; this.tx=tx; this.reservations=reservations; this.timeoutSeconds=timeoutSeconds;
    }
    public void persist(VoucherOrder order) {
        tx.execute(status -> {
            // Lock the voucher row to serialize small local-demo order writes and duplicate deliveries.
            List<Integer> stocks=db.queryForList("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=? FOR UPDATE", Integer.class, order.getVoucherId());
            if (stocks.isEmpty()) throw new IllegalStateException("Voucher missing");
            List<Map<String,Object>> existing=db.queryForList("SELECT user_id,voucher_id FROM tb_voucher_order WHERE id=?",order.getId());
            if (!existing.isEmpty()) {
                Map<String,Object> row=existing.get(0);
                if (((Number)row.get("user_id")).longValue()!=order.getUserId() ||
                        ((Number)row.get("voucher_id")).longValue()!=order.getVoucherId()) throw new IllegalStateException("Conflicting order ID");
                return null;
            }
            LocalDateTime created=order.getCreateTime()==null ? LocalDateTime.now() : order.getCreateTime();
            db.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id,status,create_time) VALUES(?,?,?,1,?)",
                    order.getId(),order.getUserId(),order.getVoucherId(),Timestamp.valueOf(created));
            int changed=db.update("UPDATE tb_seckill_voucher SET stock=stock-1 WHERE voucher_id=? AND stock>0",order.getVoucherId());
            if (changed!=1) throw new IllegalStateException("Database stock unavailable; order rolled back");
            return null;
        });
    }
    public boolean closeExpired(long orderId) {
        Boolean changed=tx.execute(status -> {
            VoucherOrder order=find(orderId);
            if (order==null) return false;
            int updated=db.update("UPDATE tb_voucher_order SET status=4,update_time=CURRENT_TIMESTAMP WHERE id=? AND status=1 AND create_time<=?",
                    orderId,Timestamp.valueOf(LocalDateTime.now().minusSeconds(timeoutSeconds)));
            if(updated!=1) return false;
            if(db.update("UPDATE tb_seckill_voucher SET stock=stock+1 WHERE voucher_id=?",order.getVoucherId())!=1)
                throw new IllegalStateException("Stock release failed; close rolled back");
            return true;
        });
        // A failed Redis release does not undo the DB close; retryReleases scans durable DB state.
        if(Boolean.TRUE.equals(changed)) { try { releaseOne(orderId); } catch(Exception e) { log.warn("Redis release pending order={}",orderId,e); } }
        return Boolean.TRUE.equals(changed);
    }
    public boolean pay(long orderId, long userId) {
        Boolean paid=tx.execute(status -> {
            VoucherOrder order=find(orderId);
            if(order==null || order.getUserId()!=userId) return false;
            if(order.getStatus()==2) return true;
            int changed=db.update("UPDATE tb_voucher_order SET status=2,pay_time=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND status=1 AND create_time>?",
                    orderId,userId,Timestamp.valueOf(LocalDateTime.now().minusSeconds(timeoutSeconds)));
            return changed==1;
        });
        return Boolean.TRUE.equals(paid);
    }
    /** Local rehearsal of a verified late payment; records a task, never calls a real refund provider. */
    public void recordLatePayment(long orderId) {
        tx.execute(status -> {
            db.update("UPDATE tb_voucher_order SET status=5 WHERE id=? AND status=4",orderId);
            db.update("INSERT INTO tb_refund_task(order_id,reason) SELECT ?,? WHERE NOT EXISTS (SELECT 1 FROM tb_refund_task WHERE order_id=?)",
                    orderId,"Local demo: verified payment arrived after cancellation",orderId);
            return null;
        });
    }
    public VoucherOrder find(long id) {
        List<VoucherOrder> rows=db.query("SELECT id,user_id,voucher_id,status,create_time FROM tb_voucher_order WHERE id=?",
                (rs,n) -> new VoucherOrder().setId(rs.getLong("id")).setUserId(rs.getLong("user_id"))
                    .setVoucherId(rs.getLong("voucher_id")).setStatus(rs.getInt("status"))
                    .setCreateTime(rs.getTimestamp("create_time").toLocalDateTime()), id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void closeBatch() {
        for(Long id:db.queryForList("SELECT id FROM tb_voucher_order WHERE status=1 AND create_time<=? ORDER BY create_time,id LIMIT 100",
                Long.class,Timestamp.valueOf(LocalDateTime.now().minusSeconds(timeoutSeconds)))) {
            try { closeExpired(id); } catch(Exception e) { log.error("Close retry failed order={}",id,e); }
        }
        retryReleases();
    }
    public void retryReleases() {
        for(Long id:db.queryForList("SELECT id FROM tb_voucher_order WHERE status IN(4,5,6) AND redis_released=0 ORDER BY id LIMIT 100",Long.class)) {
            try { releaseOne(id); } catch(Exception e) { log.error("Release retry failed order={}",id,e); }
        }
    }
    private void releaseOne(long id) {
        VoucherOrder order=find(id);
        if(order==null || order.getStatus()<4) return;
        reservations.release(order);
        db.update("UPDATE tb_voucher_order SET redis_released=1 WHERE id=? AND status IN(4,5,6)",id);
    }
}
