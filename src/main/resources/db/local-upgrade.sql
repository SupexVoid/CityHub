-- Run ONCE in a dedicated CitySeek database after importing hmdp.sql. Existing stock is not reset.
ALTER TABLE tb_voucher_order ADD COLUMN redis_released TINYINT NOT NULL DEFAULT 0;
CREATE INDEX idx_order_expiry ON tb_voucher_order(status, create_time);
CREATE INDEX idx_order_release ON tb_voucher_order(redis_released, status, id);
CREATE TABLE IF NOT EXISTS tb_cache_invalidation (
    shop_id BIGINT NOT NULL PRIMARY KEY,
    token VARCHAR(36) NOT NULL
);
CREATE TABLE IF NOT EXISTS tb_refund_task (
    order_id BIGINT NOT NULL PRIMARY KEY,
    reason VARCHAR(255) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
