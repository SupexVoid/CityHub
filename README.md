<div align="center">
  <h1>CitySeek</h1>
  <p>一个本地生活与优惠券秒杀平台</p>
</div>

基于黑马点评业务及 [CityHub 参考实现](https://github.com/Xiaoc7r/CityHub) 扩展，重点记录热点缓存、秒杀资格、异步订单、超时释放和接口限流的设计与验证。当前以本地单实例部署为目标，不包含真实支付或退款渠道。

## 当前实现

| 模块 | 实现方式 | 边界 |
| --- | --- | --- |
| 商铺二级缓存 | Caffeine → Redis → MySQL | 本地缓存最多 1000 条，写入后 5 秒过期；仅用于展示数据 |
| 缓存击穿与穿透 | 逻辑过期异步重建、缺失时互斥加载、短期空值缓存 | 有界等待、持有者校验释放锁；额外物理 TTL 回收旧副本 |
| 缓存更新 | 事务内记录失效任务，提交后删除缓存 | Redis 删除失败保留数据库任务，MQ／定时扫描补偿；最终一致 |
| 秒杀资格 | Lua 检查活动时间、库存、用户，并预占名额 | 同时保存待落单任务；单 Redis 实例，不覆盖 Redis 数据丢失恢复 |
| 异步订单 | RocketMQ 或 local 后台执行器 | 相同事务处理器，订单号防重、条件扣库存失败回滚 |
| 超时关单 | RocketMQ 延迟检查＋Spring Task 扫描 | 默认付款期 15 分钟、扫描周期 1 分钟；非实时调度保证 |
| 库存释放 | MySQL 关单与回补同事务，Redis Lua 校验订单归属释放 | 失败保留数据库待释放状态，定时重试；取消后允许重新抢券 |
| 支付竞争 | 条件 UPDATE，支付时校验用户和付款期限 | 仅 local 模拟支付；晚到支付只记录退款待办，不实际退钱 |
| 滑动窗口限流 | 注解＋AOP＋Redis ZSet＋Lua | 支持 global/IP/user；唯一请求 ID 避免同毫秒覆盖 |

## 为什么这样改

### 1. 缓存不存在，不代表商铺不存在

旧的逻辑过期读取依赖预热，缺缓存直接返回不存在；重建结束还误把数据 Key 当锁 Key 删除。现在缺失时互斥回源，未查询到的数据保存短期空标记，逻辑到期时返回旧副本并异步刷新。

Caffeine 保存短期展示副本，减少重复访问 Redis。库存与资格不进入本地缓存。缓存失效时递增版本，较早开始的回源不能覆盖较新的失效操作。额外物理 TTL 用于清理长期没有刷新成功的副本；刷新失败仍需监控处理，不能声称强一致。

本地实例主动更新会失效本地缓存。若绕过应用直接改数据库，缓存不会立即知道；多个实例的缓存广播不在本地版本范围内。

### 2. 抢到名额与创建订单分开确认

```text
请求 → Lua 检查活动／库存／重复购买
              ↓
       预扣名额＋保存待处理任务
              ↓
       投递 RocketMQ／本地后台执行器
              ↓
       数据库事务：订单防重＋扣减库存
              ↓
       移除待处理任务，允许查询订单
```

返回订单号表示请求已受理，不保证数据库订单已经可见。任务投递失败仍保留在 Redis，后台用原订单号重试；如果消费者提交数据库后退出，重复处理会识别同一订单，不重复扣库存。

Redis 待处理记录与预占在同一脚本中写入，消除应用进程在这两步之间退出的窗口。它不是生产级持久化账本：请在本地 Redis 开启合适的持久化；Redis 清空、故障丢数据后的自动全量对账不在本次实现范围内。

### 3. 关闭订单与归还名额分别保证完成

数据库只允许未支付且到期的订单改为取消，并在同一事务中归还数据库库存。Redis 名额释放在提交后执行，失败的订单仍保留 `redis_released=0`，由 Spring Task 重试。

释放脚本核对“用户当前预占的订单号”。旧订单的重复释放不会把取消后新抢到的名额释放掉。退款模拟也不会再增加一次库存。

RocketMQ 模式使用默认延迟档位安排检查；不足一个档位的剩余时间可以再次安排，消费者始终核对实际截止时间。延迟消息漏发或消费失败时，定期数据库扫描补漏。local 模式不运行 RocketMQ，超时关闭主要由扫描完成。

### 4. 状态变更成功后，后续操作仍需要恢复路径

支付与关单使用 `WHERE status=1` 条件更新。模拟支付还核对登录用户与截止时间。取消后的晚到支付只演示记录一条幂等退款待办，不能恢复已释放名额的订单，也不会调用真实支付平台。

缓存更新同样保留恢复记录：更新商铺和插入失效任务一起提交；删除失败时任务仍在 MySQL，可定时重试。两处缓存最终趋于一致，不提供强一致承诺。

## 本地启动

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

测试记录见 [本地验证记录](docs/local-verification.md)。目前不发布未经实际压测的 QPS、耗时提升比例或生产稳定性结论。

## 有意暂不实现的部分

- 真实支付、验签、退款渠道、真实短信与真实权益核销。
- 分布式调度、多实例本地缓存广播、Redis Cluster、多副本灾难恢复。
- 全量订单对账平台、自动化告警平台和复杂管理后台。
- 数据准备接口的生产权限模型。请保持本地监听，不直接当作公网服务发布。

## 待实现功能 TODO

### AI 智能客服：自然语言查询与到店预约

> 尚未实现，不计入当前功能成果。

- [ ] 评估 LangChain4j 与百炼模型接入，确认 Java 版本兼容性。
- [ ] Function Calling 查询商铺、优惠券及当前用户的权益。
- [ ] 预约业务接口、用户确认与幂等提交、本人预约记录查询。
- [ ] Redis 多轮会话记忆，按用户隔离并控制上下文长度。
- [ ] 按需要增加平台规则 RAG 检索，实时库存仍通过受控业务接口查询。
- [ ] 工具参数校验、权限检查、超时处理和调用成本记录。

## 许可证

保留原有 [MIT License](LICENSE) 及版权声明。
