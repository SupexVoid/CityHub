# CityHub

本地生活服务后端，围绕商铺浏览、内容互动与优惠券秒杀，提供用户登录、商铺查询、关注、点赞、签到和订单处理接口。

## 功能模块

| 模块 | 内容 |
| --- | --- |
| 用户 | 验证码登录、Token 校验、用户信息、签到与连续签到统计 |
| 商铺 | 商铺详情、分类查询、名称搜索、附近商铺查询 |
| 内容与社交 | 笔记发布、热门笔记、点赞、关注、共同关注与关注动态 |
| 优惠券 | 普通优惠券、秒杀优惠券、库存校验与一人一单检查 |
| 订单 | RocketMQ 异步处理、支付回调接口与超时关单消费者 |
| 访问控制 | 自定义限流注解，支持用户、IP 等维度的滑动窗口限流 |

## 技术栈

以下版本以 `pom.xml` 为准；MySQL、Redis 和 RocketMQ 服务端版本需结合部署环境选择。

| 组件 | 版本 / 用途 |
| --- | --- |
| Java | 8 |
| Spring Boot | 2.3.12.RELEASE |
| MyBatis-Plus | 3.4.3，数据访问 |
| MySQL Connector/J | 8.0.28 |
| Spring Data Redis | 2.6.2，缓存与 Redis 数据结构操作 |
| Lettuce | 6.1.6.RELEASE |
| Redisson | 3.13.6，分布式锁 |
| RocketMQ Spring Boot Starter | 2.2.3，消息生产与消费 |
| Hutool | 5.7.17，工具类 |

## 核心实现

- **缓存访问**：封装 Redis 缓存读写、空值缓存和逻辑过期处理；商铺详情查询使用逻辑过期路径，后台线程负责重建缓存。
- **秒杀校验**：通过 Lua 脚本在 Redis 中执行库存与重复购买检查，生成订单号后发送 RocketMQ 消息。
- **异步订单处理**：消费者接收秒杀订单消息，调用订单服务完成数据库写入。
- **订单状态流转**：支付回调和超时关单使用带订单状态条件的更新，约束并发状态变更。
- **滑动窗口限流**：使用注解、切面和 Redis ZSet Lua 脚本，完成窗口内请求计数与限制。
- **Redis 数据结构**：使用 Set 处理共同关注，ZSet 处理点赞与动态，GEO 查询附近商铺，BitMap 记录签到。

## 项目结构

```text
src/main/java/com/hmdp/
├── annotation/     自定义注解
├── aspect/         限流切面
├── config/         Web、MyBatis 与 Redis 相关配置
├── controller/     HTTP 接口
├── dto/            请求与响应对象
├── entity/         数据实体
├── interceptor/    登录校验与 Token 刷新
├── listener/       RocketMQ 消费者
├── mapper/         数据访问接口
├── service/        业务接口与实现
└── utils/          缓存、锁、ID 与上下文工具

src/main/resources/
├── application.yaml
├── db/hmdp.sql
├── mapper/
└── *.lua
```

## 本地运行准备

1. 准备 JDK 8、Maven、MySQL、Redis 和 RocketMQ。
2. 在专用本地数据库中导入 `src/main/resources/db/hmdp.sql`。该脚本包含建表和初始化数据，导入时会重建同名表。
3. 按本机环境调整 `application.yaml` 中的数据库连接、Redis 地址和 RocketMQ NameServer 地址。当前数据库名配置为 `dingping`，服务端口为 `8081`。
4. 检查 `RedissonConfig` 中的 Redis 连接，以及 `SystemConstants.IMAGE_UPLOAD_DIR` 中的图片上传目录。
5. 在仓库根目录启动服务：

```bash
mvn spring-boot:run
```

当前仓库包含后端源码与数据库脚本。商铺详情的逻辑过期查询依赖预热缓存；启动、联调和压测结果需在配置好外部服务后验证。

## 许可证

本项目使用 [MIT License](LICENSE)。
