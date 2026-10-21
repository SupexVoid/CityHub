# 本地运行与测试

需要 JDK 8 或 17、Maven、MySQL 8、Redis 5+。本轮构建使用 JDK 17，源码目标仍为 Java 8。Spring Boot 为 2.3.12.RELEASE，Caffeine 为 Java 8 兼容的 2.9.3。

默认只监听 `127.0.0.1:8081`，本地配置使用 Redis DB 12；建议使用单独实例或专用数据库。现有 SQL 包含重建表操作，下面脚本会拒绝覆盖已有数据库。

PowerShell 示例（mysql、redis-cli 等命令需在 PATH，或给脚本传绝对路径）：

```powershell
$env:MYSQL_PASSWORD = '你的本机 MySQL 密码'
./scripts/init-local.ps1 -Database cityseek_local
./scripts/run-local.ps1
```

可选环境变量：

| 变量 | 默认值 |
| --- | --- |
| MYSQL_HOST / MYSQL_PORT / MYSQL_DATABASE / MYSQL_USER | 127.0.0.1 / 3306 / cityseek_local / root |
| MYSQL_PASSWORD | 空，启动前按本机配置设置 |
| REDIS_HOST / REDIS_PORT / REDIS_PASSWORD | 127.0.0.1 / 6379 / 空 |
| REDIS_DATABASE | local 模式为 12 |
| ORDER_TIMEOUT_SECONDS | 900 |
| ORDER_SCAN_DELAY_MS | 60000 |

现有数据库迁移：先备份，再人工执行 `src/main/resources/db/local-upgrade.sql` 一次。该文件增加待释放标记、索引和补偿表，不会自动重置 Redis 库存。旧活动的 Redis 数据缺少活动时间和预占归属，请创建新测试活动；不要在线直接覆盖现有库存。

默认 local 使用有界后台执行器模拟消息传输，Redis 保存重试任务。它不是 RocketMQ Broker，不把这个模式的结果写成 RocketMQ 性能测试。

### RocketMQ 模式

准备本地 NameServer、Broker 和以下普通 Topic：`seckill-order-topic`、`order-timeout-topic`、`cache-delete-topic`，再启动：

```powershell
$env:ROCKETMQ_NAMESERVER = '127.0.0.1:9876'
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar --spring.profiles.active=rocketmq
```

延迟档位按 RocketMQ 默认 18 档配置编写，修改 Broker 档位后需要相应调整。该模式不开放模拟支付接口。此次验证没有运行真实 Broker，因此不宣称其投递、主从故障或死信行为已完成集成验证。

## 验证

```powershell
# 自动启动独立随机端口 Redis，使用 H2 测试事务；结束后关闭该 Redis
./scripts/test.ps1
```

旧的生成 Token、预热全表等数据准备测试标记为 `manual`，默认不执行。回归测试覆盖缓存缺失、负缓存、逻辑刷新、本地命中、失效期间的回填、事务提交／回滚、补偿任务、并发预占、重复消息、数据库回滚、提前关单、重复释放、失败重试、晚到支付待办及限流计数。

### 本地接口冒烟

为缩短等待，可在启动前设置：

```powershell
$env:ORDER_TIMEOUT_SECONDS = '5'
$env:ORDER_SCAN_DELAY_MS = '1000'
./scripts/run-local.ps1
# 另一个终端：
./scripts/smoke-local.ps1 -TimeoutSeconds 5
```

脚本在专用演示库创建测试用户和券，验证冷缓存查询、异步落单、重复购买拒绝、自动关单、释放名额、重新抢购、模拟支付和晚到支付待办。需要 `redis-cli` 能访问 local 配置对应的 Redis；使用密码时可设置 `REDISCLI_AUTH`。

常用接口：

- `POST /user/code?phone=...`：本地验证码写入 Redis，并输出到服务日志；没有真实短信。
- `POST /user/login`：使用手机号和验证码登录，返回 Token。
- `POST /voucher/seckill`：创建测试活动；本地学习版本尚未实现商家角色隔离。
- `POST /voucher-order/seckill/{voucherId}`：抢券，需要 `authorization` 请求头携带 Token。
- `GET /voucher-order/{orderId}`：查询本人的订单；异步期间可能暂不可见。
- `POST /voucher-order/pay/mock/{orderId}`：仅 local，模拟本人付款。
- `POST /voucher-order/pay/mock-late/{orderId}`：仅 local，演示取消后收款的退款待办。

测试记录见 [本地验证记录](local-verification.md)。目前不发布未经实际压测的 QPS、耗时提升比例或生产稳定性结论。

## 有意暂不实现的部分

- 真实支付、验签、退款渠道、真实短信与真实权益核销。
- 分布式调度、多实例本地缓存广播、Redis Cluster、多副本灾难恢复。
- 全量订单对账平台、自动化告警平台和复杂管理后台。
- 数据准备接口的生产权限模型。请保持本地监听，不直接当作公网服务发布。
