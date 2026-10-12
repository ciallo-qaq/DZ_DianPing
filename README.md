# DZ_DianPing 项目文档

> 项目目录：`F:\JavaProject\DZ_DianPing`
> 基础包名：`com.dzdp`　|　服务端口：`8080`　|　Java 8 + Spring Boot 2.3.12

本文档分两部分：**项目介绍**、**重难点整理**。

---

## 一、项目介绍

### 1.1 项目定位

一个"大众点评"类本地生活服务平台的后端。用户可浏览商铺与分类、发布探店笔记、点赞、关注他人并查看关注流、领取优惠券、参与秒杀抢券、查看附近商铺、每日签到。项目以**单体架构**实现，重点在于使用 Redis / 消息队列 / 分布式锁，解决高并发与缓存一致性这一类真实问题。

技术栈之外，本项目新增了两套声明式注解能力：接口限流 `@RateLimit` 与接口性能采集 `@ApiTrace`。

### 1.2 技术栈

| 分类 | 组件 | 版本 | 用途 |
|------|------|------|------|
| 基础框架 | spring-boot-starter-web | 2.3.12.RELEASE | REST 接口 |
| ORM | mybatis-plus-boot-starter | 3.4.3 | 表映射 + 分页插件 |
| 驱动 | mysql-connector-java | 8.0.28 | MySQL 8.x |
| 缓存 | spring-boot-starter-data-redis | 2.3.12 | Lettuce 连接池 |
| 分布式锁 | redisson | 3.13.6 | 秒杀一人一单锁 |
| 消息队列 | spring-boot-starter-amqp | 2.3.12 | 秒杀异步化 |
| AOP | aspectjweaver | - | 限流/采集切面 |
| 工具 | hutool-all | 5.7.17 | JSON、Bean 拷贝、UUID、正则 |
| 简化 | lombok | 1.18.32 | getter/setter/日志 |
| 测试 | spring-boot-starter-test | 2.3.12 | JUnit 5 + MockMvc |

### 1.3 运行环境

| 中间件 | 地址 | 说明 |
|--------|------|------|
| MySQL | `127.0.0.1:3308`，库 `hmdp` | 初始化脚本 `hmdp.sql` |
| Redis | `127.0.0.1:6380`，密码 `123456` | 缓存/锁/签到/点赞/GEO/Feed/全局ID/限流桶 |
| RabbitMQ | `localhost:5672`，guest/guest | 秒杀异步与死信 |

配置文件：`application.yaml`、`application-dev.yml`、`application-prod.yml`。

### 1.4 工程结构

```
src/main/java/com/dzdp/
├── dzdpApplication.java
├── config/
├── controller/
├── dto/
├── entity/
├── interceptor/                 # RefreshTokenInterceptor(order=0) + LoginInterceptor(order=1)
├── listener/                    # SeckillVoucherListener（QA/QD 两个消费者）
├── mapper/
├── ratelimit/                   # 声明式限流：注解 + 切面 + 令牌桶 + Lua
├── trace/                       # 声明式性能采集：注解 + 切面 + 上下文
├── service/ + service/impl/
└── utils/

src/main/resources/
├── application*.yml
├── rate_limit.lua
├── seckill.lua
├── unLock.lua
├── mapper/VoucherMapper.xml
└── db/hmdp.sql
```

---

## 二、重点整理

### 2.1 集群下 Session 不共享

- **隐患**：多实例部署时 Tomcat 各自持有 Session，请求被负载均衡切到另一台就"掉登录"。
- **方案**：把登录态从服务端 Session 挪到 Redis。签发不透明 Token（UUID），`login:token:{token}` 存 Hash；客户端在 `authorization` 头带 Token。
- **落地**：`UserServiceImpl#login` + `RefreshTokenInterceptor`（读 Token 换用户、续期 30 分钟）+ `UserHolder`(ThreadLocal)。
- **要点**：Token 放 Header 而不是 Cookie（降低 CSRF 面）；ThreadLocal 传递用户上下文。

### 2.2 缓存三大问题

| 问题 | 成因 | 本项目方案 | 位置 |
|------|------|-----------|------|
| 穿透 | 缓存与数据库都不存在，请求每次都打到 DB | 缓存空对象（TTL 2 分钟） | `CacheClient#queryWithPassThrough` |
| 击穿 | 热点 key 失效瞬间，大量请求同时重建 | 逻辑过期 + 互斥锁 + 异步重建，过期数据先返回 | `CacheClient#queryWithLogicalExpire` |
| 雪崩 | 大量 key 同时失效 / Redis 宕机 | 逻辑过期让 key 不真正失效；限流兜底保护下游 | 同上 + `@RateLimit` |

- **互斥锁 vs 逻辑过期**：互斥锁能保证数据强一致但会阻塞请求；逻辑过期牺牲短暂一致性换取可用性与低延迟，代价是必须做**缓存预热**且 key 常驻内存。

### 2.3 缓存与数据库一致性

- **方案**：Cache Aside —— 先更新 DB，再删缓存，且删缓存放在**事务提交之后**。
- **隐患（并发竞态）**：读线程 A 查库得到 V1 → 写线程 B 更新 DB 为 V2 并删缓存 → A 才把 V1 写回缓存 → 缓存长期是脏的。
- **进阶方案**：延迟双删（删缓存 → 更新 DB → 休眠一段时间再删一次）。
- **若引入本地缓存**：进程隔离会让多实例的 Caffeine 互不可见，脏数据无法通过"删 Redis"消除。可选 ① MQ 广播失效消息；② 重可用性，给本地缓存设很短的 TTL，容忍秒级不一致。
- **落地**：`ShopServiceImpl#update`（Cache Aside）。

### 2.4 秒杀超卖

- **隐患**：`查到库存 → 判断 → 扣减` 三步不原子，并发下多个线程都通过了判断。
- **方案**：
    1. 乐观锁版本号法：`UPDATE ... SET stock=stock-1, version=version+1 WHERE id=? AND version={旧版本}` —— 正确但**成功率过低**（冲突要重试）；
    2. CAS 法：`UPDATE ... SET stock=stock-1 WHERE voucher_id=? AND stock>0` —— 放弃版本号，只在扣减时判"还有货"，成功率大幅提升；
    3. Redis 预扣：Lua 里 `INCRBY stock -1` 与资格校验原子执行，DB 只做异步兜底扣减。
- **落地**：`seckill.lua`（Redis 层）+ `SeckillVoucherListener`（`stock>0` 条件下 `stock-1`）。
- **难点**：热点行行锁竞争激烈，进一步可做库存分片（`stock:{voucherId}:{n}` 多子库存路由）。

### 2.5 一人一单

- **隐患**：先查订单再插入，并发下两个请求都能查到 0 条。
- **方案**：
    1. 数据库唯一索引 `UNIQUE KEY (user_id, voucher_id)`
    2. Redis 原子判定：Lua 里 `SISMEMBER + SADD`，一次请求内完成"查重 + 占位"；
    3. 业务层 `count(*)` 先查后插。
- **难点**：MQ 重投或用户重试会命中"已存在"，正确语义应是**返回原订单号**而不是报错，需要幂等键。

### 2.6 分布式锁的三段演进

1. **`SETNX` 裸锁**：进程宕机导致死锁 → 必须带过期时间 `SET key value NX EX`。
2. **释放别人的锁**：业务超过过期时间，锁自动释放后被别人拿到，原线程却把它删了 → value 存线程标识（`UUID + threadId`）；再把"比对标识 + 删除"用 Lua 做成原子操作（`unLock.lua`）。
3. **仍缺的能力**：不可重入、不可重试、watchdog 自动续期 → 换 **Redisson**。
    - 可重入：Hash 结构 `key{lock} → field{threadId} → value{重入次数}`，加锁 +1、解锁 -1；
    - 可重试：基于信号量 + PubSub 的等待/唤醒；
    - 主从一致性：`RedissonMultiLock` 逐个节点加锁，全部成功才算持有。
- **落地**：`SimpleRedisLock` 与 `RedissonClient`。
- **重要认知**：`synchronized`/`ReentrantLock` 只在单 JVM 有效，多实例下锁监视器互不可见，必须用跨进程可见的锁。

### 2.7 全局唯一 ID

- **隐患**：`tb_voucher_order` 用自增主键，单表数量受限、分库分表后可能重复，且会暴露订单量。
- **方案**：`RedisIdWorker` = 时间戳(32 位，相对 2022-01-01 的秒数) << 32 | 日序列号(32 位)，序列号由 Redis `INCR icr:{prefix}:{yyyy:MM:dd}` 得到。
- **为什么用 Redis**：`INCR` 是单线程原子操作，吞吐远高于数据库自增；原子递增天然无并发隐患。
- **隐患**：Redis 宕机即无法发号 → 需要主从/哨兵 + AOF 持久化（当前未配置）。

### 2.8 秒杀链路异步化与消息可靠性

- **隐患**：`查券 → 判库存 → 查订单 → 一人一单 → 扣库存 → 建订单` 同步串联耗时过长，用户端 RT 高、系统吞吐差。
- **方案**：Redis 缓存券与资格信息 + Lua 原子判定 → 把订单信息投递到 MQ → 独立消费者做落库与扣减。
    - 先做过**阻塞队列**版本：`ArrayBlockingQueue` 存 `VoucherOrder`，单线程消费。
    - 阻塞队列的两个致命问题：① 内存队列，Redis 宕机就丢消息；② 取消息后、入库前进程崩溃同样丢数据。
- **落地**：消息队列：`seckill.lua` → `RabbitTemplate.convertAndSend("X","XA",json)` → `SeckillVoucherListener#receivedA`；QA 队列 TTL 10s，未消费则进死信交换机 Y → QD，`receivedD` 再处理一次。
- **常见业务难点**：
    - **重复消费**：消费者 ACK 前宕机，消息重投 → 需要幂等（数据库唯一索引 / 去重表 / Redis 去重 key / 状态机 CAS 流转）。
    - **Redis 扣了但 DB 没扣**：需要最终一致性与补偿任务（预扣 → 下单失败则回滚 Redis 库存）。
    - **未支付订单到期关单**：Spring Task 定时扫 / RocketMQ 延迟消息 / Redis 过期监听，三选一。
    - **关单与支付并发**：乐观锁保证二者只有一个生效。

### 2.9 Feed 流（关注流）

| 模式 | 做法 | 代价 |
|------|------|------|
| 拉模式 | 读时去所有关注人发件箱拉取再排序 | 读延迟高 |
| 推模式 | 发布时写进每个粉丝收件箱 | 写放大（粉丝 N 倍）、大 V 场景内存与网络压力大 |
| 推拉结合 | 普通用户推、大 V 由粉丝读时拉 | 实现复杂，工程上最常用 |

- **落地**：推模式 —— `BlogServiceImpl#saveBlog` 查粉丝列表后逐个 `ZADD feed:{userId}`；收件箱是 ZSet，score 为时间戳。
- **已知优化点**：当前是 `for` 循环逐个 `ZADD`，每次一次网络往返，粉丝多时 RTT 累积明显 → 可用 **pipeline 批量写**。

### 2.10 滚动分页

- **为什么不用 `limit page, size`**：ZSet 分页用下标偏移在数据变动时会重复或漏数据；且 Feed 数据量大，深分页成本高。
- **方案**：`reverseRangeByScoreWithScores(key, 0, lastId, offset, 2)` —— 用**上一次的最小时间戳**作为游标，把"同分数并列"的条数作为 `offset` 带出。
- **落地**：`BlogServiceImpl#queryBlogOfFollow` + `ScrollResult{list, minTime, offset}`。
- **细节**：同一秒发布的笔记必须靠 `offset` 去重，否则会漏条目。

### 2.11 自定义注解 + AOP

1. **注解三要素**：`@Retention(RUNTIME)`（运行期要反射读，不能是 CLASS/SOURCE）、`@Target`、属性默认值。
2. **切点表达式选型**：`@annotation(X)` 只匹配**方法**上的注解；`@within(X)` 匹配**类**上的注解（对该类所有方法生效）；`@target` 看运行期类型。
   本项目两条都写 `@within(...) || @annotation(...)`，让"打类"和"打方法"都能命中。
3. **代理机制**：Spring AOP 默认 JDK 动态代理（基于接口），无接口时用 CGLIB 子类代理。
   启动类显式声明 `@EnableAspectJAutoProxy(exposeProxy = true, proxyTargetClass = true)`，原因有二：Controller 没有接口；且存在"按实现类注入"的写法（`FollowServiceImpl` 直接注入 `UserServiceImpl`）——统一 CGLIB 才能两者兼得。
4. **自调用失效**：同类内部 `this.method()` 不走代理，注解会失效。解法：`AopContext.currentProxy()`（所以才要 `exposeProxy = true`）、注入自身、或 AspectJ 编译期织入。
5. **切面不能影响业务**：采集/限流这类"附加能力"的所有动作都必须可失败。
   `ApiTraceAspect` 里采集异常只降级成一条 WARN；`RateLimitKeyResolver` 解析不出标识时**放行并告警**，而不是把请求卡死。
6. **非 Web 上下文**：切面里通过 `RequestContextHolder` 取请求，异步线程/定时任务里取不到，必须判空降级（项目里返回 null → 放行，而不是抛异常）。
7. **覆盖范围**：注解只对 Controller 生效，Service 内部互相调用不受限流保护。

### 2.12 限流器的细节

- **桶 key 必须不可伪造**：`X-Forwarded-For` 是客户端可写的，若直接拼进 key，攻击者可以伪造 header 把自己"搬"到别人的桶或空桶里。
  项目做法：优先 `X-Real-IP`（由 nginx 覆写），否则取 XFF **最后一跳**，再做字符白名单过滤（`|` 不在白名单内，因此分隔符不可伪造），并限制长度上限 64。
- **failOpen 语义**：Redis 挂掉时，验证码/秒杀这类接口用 `failOpen=false`（宁可快速失败，也不放流量进来），普通查询用 `true` 保可用性。
- **key 自动回收**：桶 TTL = 补满整桶所需时间的 2 倍，避免 key 无界增长（`RateLimitSpec#ttlMillis`）。

### 2.13 性能采集的取舍

- 简单的开发期调优工具。
- 采集结果只做**聚合**：同一 Service 方法一次请求内多次调用合并为 `xN`，用于 N+1 查询问题发现。

---

## 三、杂

### 3.1 限流算法选择

1. **固定窗口**：时间划分为固定大小窗口，超过 cnt_max 则进入下个窗口 → 隐患：临界点允许两倍流量。
2. **滑动窗口**：永远统计最近 N 个桶的流量 → 隐患：窗口边界重置，流量集中边界涌入。
3. **漏桶算法**：以固定速率处理流量，平滑输出 → 隐患：不能处理突发流量。
4. **令牌桶算法**：Redis hash + Lua 实现，token 生成速率 == 平均请求速率，桶容量 == 突发流量。

### 3.2 其他 Redis 特性应用

**点赞排行榜（Redis ZSet）**
- 需求：按时间排序的点赞列表。
- 实现：`ZADD key score(时间戳) value(userId)`。

**共同关注（Redis Set）**
- 需求：A 关注的人和 B 关注的人取交集。
- 实现：`SINTER keyA keyB`。

**附近商户（Redis GEO）**
- 需求：搜索方圆 5km 内的店，按距离排序。
- 实现：底层是 GeoHash 算法。命令：`GEORADIUS` 或 `GEOSEARCH`。

**用户签到（Redis BitMap）**
- 需求：记录用户一年的签到，极其节省空间。
- 实现：1 个 bit 代表一天。`SETBIT key offset 1`。

**UV 统计（HyperLogLog）**
- 需求：统计网站即使百万级访问量，误差允许极小。
- 实现：概率算法，占用内存极小（12KB）。命令：`PFADD`、`PFCOUNT`。