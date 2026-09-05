# CityHub (hm-dianping) 后端项目深度拆解报告

> **分析日期**: 2026-07-13
> **项目名称**: hm-dianping（大众点评类生活服务平台）
> **基础包名**: `com.hmdp`
> **作者**: 虎哥

---

## 一、基础信息

### 1.1 项目类型

| 维度 | 详情 |
|------|------|
| **架构模式** | 单体架构（Monolithic） |
| **开发语言** | Java 8 |
| **核心框架** | Spring Boot 2.3.12.RELEASE |
| **ORM 框架** | MyBatis-Plus 3.4.3 |
| **构建工具** | Maven |
| **服务端口** | 8081 |

### 1.2 核心依赖清单

| 分类 | 依赖 | 版本 | 用途 |
|------|------|------|------|
| **Web 框架** | spring-boot-starter-web | 2.3.12 | RESTful API 服务 |
| **ORM** | mybatis-plus-boot-starter | 3.4.3 | 数据库 ORM + 分页 |
| **数据库驱动** | mysql-connector-java | 8.0.28 | MySQL 8.x 连接 |
| **Redis 基础** | spring-boot-starter-data-redis | 2.3.12 | Redis 集成（排除了内置的 lettuce/spring-data-redis） |
| **Redis 客户端** | lettuce-core | 6.1.6.RELEASE | Redis 连接池客户端 |
| **Spring Data Redis** | spring-data-redis | 2.6.2 | 单独升级版本 |
| **Redis 连接池** | commons-pool2 | - | Lettuce 连接池依赖 |
| **分布式锁** | redisson | 3.13.6 | Redisson 分布式锁 |
| **消息队列** | spring-boot-starter-amqp | 2.3.12 | RabbitMQ 消息队列 |
| **工具库** | hutool-all | 5.7.17 | 综合工具类库 |
| **AOP** | aspectjweaver | - | AOP 切面支持 |
| **简化代码** | lombok | - | 注解生成 getter/setter 等 |

### 1.3 中间件一览

| 中间件 | 连接地址 | 用途 |
|--------|----------|------|
| **MySQL** | `127.0.0.1:3306` / `dingping` 库 | 主数据库 |
| **Redis** | `127.0.0.1:6379` | 缓存、分布式锁、签到、点赞、地理位置 |
| **RabbitMQ** | `localhost:5672` / guest:guest | 秒杀异步下单消息队列 |

### 1.4 环境配置

- 仅有单一 `application.yaml` 配置文件，**无 dev/prod/test 环境区分**
- 敏感信息（数据库密码、RabbitMQ 密码）**明文硬编码**在配置文件中

---

## 二、目录结构逐行解析

```
CityHub-main/
├── pom.xml                          # Maven 项目描述文件
├── README.md                        # 项目说明文件
├── LICENSE                          # 许可证文件
├── .idea/                           # IntelliJ IDEA 项目配置目录（IDE 生成）
│   ├── .gitignore
│   ├── compiler.xml                 # 编译器配置
│   ├── encodings.xml                # 编码配置
│   ├── jarRepositories.xml          # Maven 仓库配置
│   ├── misc.xml                     # 杂项配置
│   ├── modules.xml                  # 模块配置
│   └── workspace.xml                # 工作区配置
└── src/
    ├── main/
    │   ├── java/com/hmdp/
    │   │   ├── HmDianPingApplication.java      # 【启动类】Spring Boot 入口
    │   │   ├── config/                          # 【配置层】
    │   │   │   ├── MvcConfig.java               #   - MVC 拦截器注册配置
    │   │   │   ├── MybatisConfig.java            #   - MyBatis-Plus 分页插件配置
    │   │   │   ├── QueueConfig.java              #   - RabbitMQ 队列/交换机/绑定配置
    │   │   │   ├── RedissonConfig.java           #   - Redisson 客户端配置
    │   │   │   └── WebExceptionAdvice.java       #   - 全局异常处理器
    │   │   ├── controller/                      # 【控制层】REST 接口
    │   │   │   ├── UserController.java           #   - 用户：登录、验证码、签到
    │   │   │   ├── ShopController.java           #   - 商铺：CRUD、按类型/名称查询
    │   │   │   ├── ShopTypeController.java       #   - 商铺分类：分类列表
    │   │   │   ├── BlogController.java           #   - 探店笔记：发布、点赞、热点、关注流
    │   │   │   ├── BlogCommentsController.java   #   - 评论接口（空壳，未实现）
    │   │   │   ├── VoucherController.java        #   - 优惠券：新增普通券/秒杀券、查询
    │   │   │   ├── VoucherOrderController.java   #   - 优惠券订单：秒杀下单
    │   │   │   ├── FollowController.java         #   - 关注：关注/取关、共同关注
    │   │   │   └── UploadController.java         #   - 文件上传：图片上传/删除
    │   │   ├── dto/                             # 【数据传输对象】
    │   │   │   ├── Result.java                   #   - 统一响应结果封装
    │   │   │   ├── LoginFormDTO.java             #   - 登录表单入参
    │   │   │   ├── UserDTO.java                  #   - 用户脱敏传输对象
    │   │   │   └── ScrollResult.java             #   - 滚动分页结果封装
    │   │   ├── entity/                           # 【实体层】数据库表映射
    │   │   │   ├── User.java                     #   - tb_user
    │   │   │   ├── UserInfo.java                 #   - tb_user_info
    │   │   │   ├── Shop.java                     #   - tb_shop
    │   │   │   ├── ShopType.java                 #   - tb_shop_type
    │   │   │   ├── Blog.java                     #   - tb_blog
    │   │   │   ├── BlogComments.java             #   - tb_blog_comments
    │   │   │   ├── Voucher.java                  #   - tb_voucher
    │   │   │   ├── SeckillVoucher.java           #   - tb_seckill_voucher
    │   │   │   ├── VoucherOrder.java             #   - tb_voucher_order
    │   │   │   └── Follow.java                   #   - tb_follow
    │   │   ├── interceptor/                      # 【拦截器层】
    │   │   │   ├── RefreshTokenInterceptor.java  #   - Token 刷新拦截器（order=0，最先执行）
    │   │   │   └── LoginInterceptor.java         #   - 登录校验拦截器（order=1）
    │   │   ├── listener/                         # 【消息监听层】
    │   │   │   └── SeckillVoucherListener.java   #   - RabbitMQ 秒杀订单消费者
    │   │   ├── mapper/                           # 【持久层】MyBatis-Plus Mapper
    │   │   │   ├── UserMapper.java               #   - User 表
    │   │   │   ├── UserInfoMapper.java           #   - UserInfo 表
    │   │   │   ├── ShopMapper.java               #   - Shop 表
    │   │   │   ├── ShopTypeMapper.java           #   - ShopType 表
    │   │   │   ├── BlogMapper.java               #   - Blog 表
    │   │   │   ├── BlogCommentsMapper.java       #   - BlogComments 表
    │   │   │   ├── VoucherMapper.java            #   - Voucher 表（含自定义 SQL）
    │   │   │   ├── SeckillVoucherMapper.java     #   - SeckillVoucher 表
    │   │   │   ├── VoucherOrderMapper.java       #   - VoucherOrder 表
    │   │   │   └── FollowMapper.java             #   - Follow 表
    │   │   ├── service/                          # 【服务层接口】
    │   │   │   ├── IUserService.java
    │   │   │   ├── IUserInfoService.java
    │   │   │   ├── IShopService.java
    │   │   │   ├── IShopTypeService.java
    │   │   │   ├── IBlogService.java
    │   │   │   ├── IBlogCommentsService.java
    │   │   │   ├── IVoucherService.java
    │   │   │   ├── ISeckillVoucherService.java
    │   │   │   ├── IVoucherOrderService.java
    │   │   │   ├── IFollowService.java
    │   │   │   └── impl/                         # 【服务层实现】
    │   │   │       ├── UserServiceImpl.java       #   - 登录/验证码/签到
    │   │   │       ├── UserInfoServiceImpl.java   #   - 用户详情（空实现）
    │   │   │       ├── ShopServiceImpl.java       #   - 商铺缓存/附近商铺搜索
    │   │   │       ├── ShopTypeServiceImpl.java   #   - 分类列表缓存
    │   │   │       ├── BlogServiceImpl.java       #   - 笔记发布/点赞/关注流
    │   │   │       ├── BlogCommentsServiceImpl.java # - 评论（空实现）
    │   │   │       ├── VoucherServiceImpl.java    #   - 优惠券/秒杀券创建
    │   │   │       ├── SeckillVoucherServiceImpl.java # - 秒杀券（空实现）
    │   │   │       ├── VoucherOrderServiceImpl.java  # - 秒杀下单核心逻辑
    │   │   │       └── FollowServiceImpl.java     #   - 关注/取关/共同关注
    │   │   └── utils/                            # 【工具类层】
    │   │       ├── CacheClient.java               #   - 缓存客户端（穿透/逻辑过期）
    │   │       ├── RedisConstants.java            #   - Redis Key 常量
    │   │       ├── SystemConstants.java           #   - 系统常量
    │   │       ├── RedisIdWorker.java             #   - 全局唯一 ID 生成器
    │   │       ├── RedisData.java                 #   - 缓存数据包装（含逻辑过期时间）
    │   │       ├── UserHolder.java                #   - ThreadLocal 用户上下文
    │   │       ├── PasswordEncoder.java           #   - 密码加密（MD5+盐值）
    │   │       ├── SimpleRedisLock.java           #   - 基于 Redis 的分布式锁
    │   │       ├── ILock.java                     #   - 分布式锁接口
    │   │       ├── RegexUtils.java                #   - 正则校验工具
    │   │       └── RegexPatterns.java             #   - 正则表达式常量
    │   └── resources/
    │       ├── application.yaml                  # 主配置文件
    │       ├── seckill.lua                       # 秒杀 Lua 脚本
    │       ├── unLock.lua                        # 分布式锁释放 Lua 脚本
    │       ├── db/
    │       │   └── hmdp.sql                      # 数据库初始化 SQL
    │       └── mapper/
    │           └── VoucherMapper.xml             # Voucher 自定义 SQL 映射
    └── test/java/com/hmdp/
        ├── HmDianPingApplicationTests.java      # 单元测试
        ├── ShopCacheTest.java                    # 商铺缓存测试
        ├── VoucherOrderControllerTest.java       # 秒杀订单测试
        └── tokens.txt                            # 测试用 token 文件
```

---

## 三、启动全流程

### 3.1 启动入口

`F:\JavaProject\CityHub-main\src\main\java\com\hmdp\HmDianPingApplication.java`

```java
@EnableAspectJAutoProxy(exposeProxy = true)  // 开启 AOP，暴露代理对象
@MapperScan("com.hmdp.mapper")               // 扫描 Mapper 接口
@EnableRabbit                                 // 启用 RabbitMQ 监听
@SpringBootApplication
public class HmDianPingApplication {
    public static void main(String[] args) {
        SpringApplication.run(HmDianPingApplication.class, args);
    }
}
```

### 3.2 启动加载流程（按执行顺序）

```
1. Spring Boot 启动 → 扫描 application.yaml
   ├── server.port=8081
   ├── 数据源配置：MySQL jdbc:mysql://127.0.0.1:3306/dingping
   ├── Redis 配置：127.0.0.1:6379 + Lettuce 连接池
   ├── RabbitMQ 配置：localhost:5672 guest/guest
   └── Jackson 配置：忽略 null 字段

2. 自动配置阶段
   ├── DataSource 自动配置 → HikariCP 连接池初始化
   ├── Redis 自动配置 → StringRedisTemplate Bean 注册
   ├── RabbitMQ 自动配置 → ConnectionFactory + RabbitTemplate
   └── Jackson 自动配置 → ObjectMapper 全局序列化规则

3. 自定义 Bean 注册（config 包）
   ├── MybatisConfig → MybatisPlusInterceptor（分页插件）
   ├── MvcConfig → 注册拦截器链
   │   ├── RefreshTokenInterceptor（order=0，所有路径）
   │   └── LoginInterceptor（order=1，排除登录等公开路径）
   ├── RedissonConfig → RedissonClient Bean
   └── QueueConfig → RabbitMQ 声明交换机/队列/绑定
       ├── 交换机 X（普通）、Y（死信）
       ├── 队列 QA（普通队列，TTL=10s，绑定死信 YD）
       └── 队列 QD（死信队列）

4. MyBatis-Plus 初始化
   ├── @MapperScan("com.hmdp.mapper") 扫描所有 Mapper
   ├── 实体类别名扫描：com.hmdp.entity
   └── 分页插件生效（MySQL 方言）

5. 服务监听启动
   ├── @RabbitListener 消费者启动监听
   │   ├── SeckillVoucherListener.receivedA() → 队列 QA
   │   └── SeckillVoucherListener.receivedD() → 队列 QD
   └── Tomcat 启动 → 端口 8081 → 应用就绪
```

---

## 四、分层架构详解

### 4.1 架构分层图

```
┌─────────────────────────────────────────┐
│           Controller 层                  │  ← REST 接口暴露
│  接收 HTTP 请求，参数校验，调用 Service   │
├─────────────────────────────────────────┤
│           Service 层                     │  ← 业务逻辑
│  接口定义 + Impl 实现，事务管理           │
├─────────────────────────────────────────┤
│           Mapper 层                      │  ← 数据访问
│  MyBatis-Plus BaseMapper + 自定义 SQL     │
├─────────────────────────────────────────┤
│           Entity 层                      │  ← 数据库映射
│  POJO + @TableName + @TableId            │
├─────────────────────────────────────────┤
│  Interceptor / Listener / Utils          │  ← 横切关注点
│  拦截器、消息监听、工具类                 │
└─────────────────────────────────────────┘
```

### 4.2 各层职责与数据流转规则

| 层次 | 职责 | 输入 | 输出 |
|------|------|------|------|
| **Controller** | 接收 HTTP 请求、参数绑定、调用 Service | HTTP Request（JSON/表单） | `Result<T>` 统一响应 |
| **Service** | 核心业务逻辑、事务控制、缓存策略 | DTO / 基本类型 | `Result<T>` 或实体 |
| **Mapper** | 数据库 CRUD、自定义 SQL | 实体 / SQL 参数 | 实体集合 |
| **Interceptor** | 请求前置处理（Token 刷新、登录校验） | HttpServletRequest | 放行/401 拦截 |
| **Listener** | 异步消息消费（秒杀订单） | RabbitMQ Message | 入库 + 减库存 |

### 4.3 数据流转规则

```
HTTP 请求
  → RefreshTokenInterceptor（Redis 查 Token → ThreadLocal 存用户）
  → LoginInterceptor（检查 ThreadLocal 中是否有用户 → 无则 401）
  → Controller（参数绑定，调用 Service）
  → Service（业务处理，可能涉及 Redis 缓存/分布式锁/RabbitMQ）
  → Mapper（MyBatis-Plus 操作数据库）
  → 返回 Result<T> JSON 响应
```

### 4.4 统一返回格式

由 `dto/Result.java` 定义：

```java
public class Result {
    private Boolean success;   // 成功/失败
    private String errorMsg;   // 错误信息（成功时 null）
    private Object data;       // 业务数据
    private Long total;        // 分页总数
}
```

**使用方式**：
- 成功无数据：`Result.ok()`
- 成功有数据：`Result.ok(data)`
- 成功分页数据：`Result.ok(data, total)`
- 失败：`Result.fail("错误信息")`

**JSON 输出示例**（jackson 忽略 null 字段）：
```json
// 成功
{"success": true, "data": {...}}
// 失败
{"success": false, "errorMsg": "手机号格式错误"}
```

---

## 五、核心组件拆解

### 5.1 全局拦截器

#### 5.1.1 RefreshTokenInterceptor（Token 刷新拦截器）

- **文件**: `interceptor/RefreshTokenInterceptor.java`
- **优先级**: order=0（最先执行）
- **拦截路径**: `/**`（所有路径）
- **核心逻辑**:
  1. 从请求头 `authorization` 获取 Token
  2. 以 `login:token:{token}` 为 Key 从 Redis Hash 中获取用户信息
  3. 若存在，转为 `UserDTO` 存入 `UserHolder`（ThreadLocal）
  4. 刷新 Redis 中 Token 过期时间为 30 分钟
  5. **始终返回 true**（不放行由后续 LoginInterceptor 决定）

#### 5.1.2 LoginInterceptor（登录校验拦截器）

- **文件**: `interceptor/LoginInterceptor.java`
- **优先级**: order=1（后执行）
- **排除路径**:
  - `/user/login` - 登录接口
  - `/user/code` - 验证码接口
  - `/upload/**` - 文件上传
  - `/voucher/**` - 优惠券查询
  - `/shop/**` - 商铺查询
  - `/shop-type/**` - 分类查询
  - `/blog/hot` - 热门笔记
- **核心逻辑**:
  1. 检查 `UserHolder.getUser()` 是否为空
  2. 若为空 → 设置 HTTP 状态码 401 → 拦截请求
  3. 若非空 → 放行

### 5.2 全局异常处理器

- **文件**: `config/WebExceptionAdvice.java`
- **注解**: `@RestControllerAdvice`
- **当前实现**: 仅捕获 `RuntimeException`，返回 `Result.fail("服务器异常")`
- **不足**: 未对业务异常、参数校验异常、数据库异常等进行细分处理

### 5.3 工具类详解

| 工具类 | 文件 | 功能描述 |
|--------|------|----------|
| **CacheClient** | `utils/CacheClient.java` | 通用缓存客户端，封装「缓存穿透」和「逻辑过期」两种策略，提供泛型方法 |
| **RedisIdWorker** | `utils/RedisIdWorker.java` | 全局唯一 ID 生成器，使用时间戳(32位)+自增序列(32位)拼接，基于 Redis INCR |
| **UserHolder** | `utils/UserHolder.java` | 基于 ThreadLocal 存储/获取当前请求用户信息 |
| **PasswordEncoder** | `utils/PasswordEncoder.java` | 密码加密：随机盐值(20位)+MD5，格式 `salt@md5(password+salt)` |
| **SimpleRedisLock** | `utils/SimpleRedisLock.java` | 基于 Redis SETNX 的简易分布式锁，释放使用 Lua 脚本保证原子性 |
| **ILock** | `utils/ILock.java` | 分布式锁接口，定义 `tryLock` 和 `delLock` |
| **RedisData** | `utils/RedisData.java` | 缓存数据包装类，包含 `expireTime`（逻辑过期时间）和 `data` |
| **RegexUtils** | `utils/RegexUtils.java` | 正则校验：手机号、邮箱、验证码 |
| **RegexPatterns** | `utils/RegexPatterns.java` | 正则表达式常量定义 |
| **RedisConstants** | `utils/RedisConstants.java` | Redis Key 前缀和 TTL 常量 |
| **SystemConstants** | `utils/SystemConstants.java` | 系统常量：上传路径、默认分页大小、用户昵称前缀 |

### 5.4 缓存策略

#### 5.4.1 缓存穿透方案

`CacheClient.queryWithPassThrough()` 方法：
1. 查 Redis → 命中则直接返回
2. 未命中 → 查数据库
3. 数据库也不存在 → 向 Redis 写入空值（TTL=2分钟），防止穿透
4. 数据库存在 → 写入 Redis 并返回

#### 5.4.2 缓存击穿方案（逻辑过期）

`CacheClient.queryWithLogicalExpire()` 方法：
1. 查 Redis → 未命中返回 null
2. 命中 → 检查 `expireTime` 是否过期
3. 未过期 → 直接返回
4. 已过期 → 尝试获取互斥锁
   - 获取成功 → 开启独立线程异步重建缓存 → 返回旧数据
   - 获取失败 → 直接返回旧数据（牺牲一致性，保证可用性）

#### 5.4.3 商铺更新策略

`ShopServiceImpl.update()`：
```java
@Transactional
public Result update(Shop shop) {
    updateById(shop);                                    // 先更新数据库
    stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId()); // 再删除缓存
    return Result.ok();
}
```
采用 **Cache Aside（旁路缓存）** 模式：先写数据库，再删缓存。

### 5.5 分布式锁实现

项目中涉及两套分布式锁方案：

| 方案 | 实现类 | 使用场景 |
|------|--------|----------|
| **简单 Redis 锁** | `SimpleRedisLock` | 基于 SETNX + Lua 脚本释放 |
| **Redisson 锁** | `RedissonClient` | 秒杀场景，但当前代码中被注释，改用 RabbitMQ 异步方案 |

### 5.6 事务管理

- 使用 `@Transactional` 注解（Spring 声明式事务）
- 涉及位置：
  - `ShopServiceImpl.update()` - 商铺更新
  - `VoucherServiceImpl.addSeckillVoucher()` - 秒杀券创建（券+秒杀信息双表写入）
  - `VoucherOrderServiceImpl.createVoucherOrder()` - 订单创建（减库存+保存订单）

### 5.7 定时任务

**项目中无定时任务实现**（无 `@Scheduled` 注解）。

### 5.8 RabbitMQ 消息队列

#### 5.8.1 队列架构

```
生产者(VoucherOrderServiceImpl)
    │
    ▼
普通交换机 X ────► QA（普通队列，TTL=10s）
                      │
                      │ 消息超时
                      ▼
              死信交换机 Y ────► QD（死信队列）
```

#### 5.8.2 消费者

- `SeckillVoucherListener.receivedA()`：消费 QA 队列 → 保存订单到数据库 → 数据库减库存
- `SeckillVoucherListener.receivedD()`：消费 QD 死信队列 → 同样保存订单减库存

### 5.9 Lua 脚本

| 脚本文件 | 用途 |
|----------|------|
| `seckill.lua` | 秒杀判断：检查库存 → 检查是否重复下单 → 扣减库存 → 发送消息到 Redis Stream |
| `unLock.lua` | 分布式锁释放：比对线程标识一致才 DEL |

---

## 六、业务模块拆分

### 6.1 业务模块总览

| 模块 | Controller | 核心 Service | 数据表 | 主要功能 |
|------|------------|-------------|--------|----------|
| **用户模块** | `UserController` | `UserServiceImpl` | `tb_user`, `tb_user_info` | 登录、验证码、签到 |
| **商铺模块** | `ShopController` | `ShopServiceImpl` | `tb_shop` | 商铺查询（缓存/附近搜索）、更新 |
| **分类模块** | `ShopTypeController` | `ShopTypeServiceImpl` | `tb_shop_type` | 分类列表（Redis List 缓存） |
| **笔记模块** | `BlogController` | `BlogServiceImpl` | `tb_blog` | 发布笔记、点赞、热点、关注流 |
| **评论模块** | `BlogCommentsController` | `BlogCommentsServiceImpl` | `tb_blog_comments` | **未实现**（空壳） |
| **优惠券模块** | `VoucherController` | `VoucherServiceImpl` | `tb_voucher` | 创建普通券/秒杀券 |
| **秒杀订单模块** | `VoucherOrderController` | `VoucherOrderServiceImpl` | `tb_voucher_order`, `tb_seckill_voucher` | 秒杀下单（Lua脚本+RabbitMQ异步） |
| **关注模块** | `FollowController` | `FollowServiceImpl` | `tb_follow` | 关注/取关、共同关注（Redis Set） |
| **上传模块** | `UploadController` | - | - | 图片上传到本地磁盘 |

### 6.2 各模块业务逻辑简述

#### 用户模块
- **验证码发送**：校验手机号格式 → 生成6位随机数 → 存入 Redis（TTL=2分钟）→ 日志打印（未真正发送短信）
- **登录**：校验验证码 → 查数据库有无用户 → 无则自动注册 → 生成 UUID Token → 用户信息存 Redis Hash → 返回 Token
- **签到**：基于 Redis BitMap，Key=`sign:{userId}:{yyyyMM}`，offset=日期-1
- **连续签到统计**：使用 `BITFIELD` 命令获取当月签到位图 → 位运算统计连续天数

#### 商铺模块
- **查询商铺**：使用 `CacheClient.queryWithLogicalExpire()` 逻辑过期方案防击穿
- **附近商铺搜索**：Redis GEO 数据结构 → `GEOSEARCH` 按坐标搜索5km内 → 分页返回
- **更新商铺**：先更新 DB → 删除 Redis 缓存（Cache Aside 模式）

#### 笔记模块
- **发布笔记**：保存到 DB → 查询粉丝列表 → 推送到每个粉丝的 Feed 收件箱（Redis ZSet）
- **点赞**：Redis ZSet（`blog:liked:{id}`），score=时间戳，支持点赞/取消
- **点赞排行**：`ZREVRANGE` 获取 Top5
- **关注流（滚动分页）**：`ZREVRANGEBYSCORE` 从收件箱获取，lastId+offset 实现滚动分页

#### 秒杀模块
- **下单流程**：执行 `seckill.lua`（原子校验库存+一人一单+扣库存）→ 发送 JSON 消息到 RabbitMQ → 异步消费入库
- **全局 ID**：`RedisIdWorker` 生成（Redis INCR + 时间戳拼接）
- **死信队列**：QA 队列 TTL=10s，超时消息进入 QD，再次尝试处理

#### 关注模块
- **关注/取关**：写 DB + 更新 Redis Set（`follows:{userId}`）
- **共同关注**：Redis Set 求交集 `SINTER`

---

## 七、数据库

### 7.1 数据表清单

| 表名 | 实体类 | 主键策略 | 记录数 |
|------|--------|----------|--------|
| `tb_user` | `User.java` | `IdType.AUTO`（自增） | 1000+ |
| `tb_user_info` | `UserInfo.java` | `IdType.AUTO`（user_id 即主键） | 0 |
| `tb_shop` | `Shop.java` | `IdType.AUTO` | ~14 |
| `tb_shop_type` | `ShopType.java` | `IdType.AUTO` | 10 |
| `tb_blog` | `Blog.java` | `IdType.AUTO` | ~23 |
| `tb_blog_comments` | `BlogComments.java` | `IdType.AUTO` | 0 |
| `tb_voucher` | `Voucher.java` | `IdType.AUTO` | ~10 |
| `tb_seckill_voucher` | `SeckillVoucher.java` | `IdType.INPUT`（手动） | - |
| `tb_voucher_order` | `VoucherOrder.java` | `IdType.INPUT`（手动） | 0 |
| `tb_follow` | `Follow.java` | `IdType.AUTO` | - |
| `tb_sign` | 无实体 | - | 0 |

### 7.2 主键策略说明

| 策略 | 使用表 | 说明 |
|------|--------|------|
| **AUTO（自增）** | 大部分表 | MySQL 自增主键，简单可靠 |
| **INPUT（手动赋值）** | `tb_seckill_voucher`、`tb_voucher_order` | `SeckillVoucher.voucherId` 关联 `Voucher.id`；`VoucherOrder.id` 使用 `RedisIdWorker` 生成全局唯一 ID |

### 7.3 实体关联关系

```
User (tb_user)
 ├── 1:1 → UserInfo (tb_user_info)    user_id 关联
 ├── 1:N → Blog (tb_blog)             user_id 关联
 ├── 1:N → VoucherOrder (tb_voucher_order)  user_id 关联
 └── N:M → Follow (tb_follow)         user_id / follow_user_id

Shop (tb_shop)
 ├── N:1 → ShopType (tb_shop_type)    type_id 关联
 ├── 1:N → Voucher (tb_voucher)       shop_id 关联
 └── 1:N → Blog (tb_blog)             shop_id 关联

Voucher (tb_voucher)
 └── 1:1 → SeckillVoucher (tb_seckill_voucher)  voucher_id 关联

Blog (tb_blog)
 └── 1:N → BlogComments (tb_blog_comments)  blog_id 关联
```

### 7.4 索引设计

从 SQL DDL 分析，**所有表只有主键索引**（`PRIMARY KEY`），**无额外的二级索引**。这意味着：

- `tb_user.phone` 无唯一索引（但业务中要求手机号唯一）
- `tb_shop.type_id` 无索引（按类型查询是高频操作）
- `tb_blog.user_id` 无索引（按用户查询笔记）
- `tb_follow(user_id, follow_user_id)` 无联合索引
- `tb_voucher_order(user_id, voucher_id)` 无联合索引（一人一单校验依赖）

### 7.5 VoucherMapper.xml 自定义 SQL

```xml
<select id="queryVoucherOfShop" resultType="com.hmdp.entity.Voucher">
    SELECT v.*, sv.stock, sv.begin_time, sv.end_time
    FROM tb_voucher v
    LEFT JOIN tb_seckill_voucher sv ON v.id = sv.voucher_id
    WHERE v.shop_id = #{shopId} AND v.status = 1
</select>
```
连表查询优惠券+秒杀信息，返回的 `stock`、`beginTime`、`endTime` 字段在 `Voucher` 实体中标记为 `@TableField(exist = false)`。

---

## 八、补充内容

### 8.1 接口统一返回格式

```json
// 成功 - 无数据
{"success": true}

// 成功 - 有数据
{"success": true, "data": { "id": 1, "name": "商铺名" }}

// 成功 - 分页数据
{"success": true, "data": [...], "total": 100}

// 失败
{"success": false, "errorMsg": "手机号格式错误"}
```

jackson 配置 `default-property-inclusion: non_null`，null 字段自动省略。

### 8.2 项目编码规范

| 项目 | 规范 |
|------|------|
| **包命名** | `com.hmdp.{layer}` |
| **类命名** | Controller：`XxxController`；Service 接口：`IXxxService`；实现：`XxxServiceImpl`；Mapper：`XxxMapper` |
| **实体类** | 使用 Lombok `@Data`、`@Accessors(chain = true)` 支持链式调用 |
| **表映射** | MyBatis-Plus `@TableName("tb_xxx")` 显式指定表名 |
| **DTO** | `LoginFormDTO`、`UserDTO`（脱敏）、`ScrollResult`（滚动分页） |
| **常量** | 分类存放于 `RedisConstants`、`SystemConstants` |
| **日志** | Lombok `@Slf4j`，日志级别 debug |
| **依赖注入** | 混合使用 `@Resource` 和 `@Autowired`（不规范，应统一） |

### 8.3 代码缺陷与架构优化建议

#### 🔴 严重缺陷

1. **敏感信息明文硬编码**
   - `application.yaml` 中 MySQL 密码 `290390`、RabbitMQ 密码 `guest` 明文暴露
   - **建议**：使用环境变量或配置中心（Nacos/Apollo）管理敏感配置

2. **无环境隔离**
   - 仅有一个 `application.yaml`，dev/prod/test 未分离
   - **建议**：拆分为 `application-dev.yml`、`application-prod.yml`，通过 `spring.profiles.active` 切换

3. **数据库索引缺失**
   - `tb_user.phone` 无唯一索引（高并发下可能插入重复手机号）
   - `tb_follow(user_id, follow_user_id)` 无联合索引
   - `tb_voucher_order(user_id, voucher_id)` 无联合索引
   - `tb_shop.type_id` 无索引
   - **建议**：根据查询模式添加必要索引

4. **用户模块：密码字段定义了但登录逻辑中完全未使用**
   - `UserServiceImpl.login()` 方法忽略密码，仅使用验证码登录
   - `LoginFormDTO.password` 字段未被消费
   - **建议**：明确是否需要密码登录通道，或移除冗余字段

#### 🟡 中等问题

5. **异常处理过于粗糙**
   - 全局异常处理器仅捕获 `RuntimeException`，统一返回 "服务器异常"
   - 未区分业务异常、参数校验异常、系统异常
   - **建议**：定义业务异常体系（如 `BizException`），按异常类型返回不同错误码和消息

6. **依赖注入风格不一致**
   - 混用 `@Resource`（按名称注入）和 `@Autowired`（按类型注入）
   - **建议**：统一为 `@RequiredArgsConstructor`（构造器注入）或统一 `@Resource`

7. **线程池使用不规范**
   - `CacheClient` 中使用 `Executors.newFixedThreadPool(10)` 直接创建线程池
   - **建议**：使用 Spring 的 `ThreadPoolTaskExecutor` 统一管理，便于监控

8. **RabbitMQ 死信队列消费者逻辑重复**
   - `receivedA()` 和 `receivedD()` 中保存订单+减库存代码完全重复
   - **建议**：抽取公共方法，DRY 原则

9. **注释大段废弃代码**
   - `ShopServiceImpl`、`VoucherOrderServiceImpl` 中存在大量被注释掉的旧代码
   - **建议**：删除或使用 Git 历史管理，避免代码膨胀

10. **文件上传路径硬编码**
    - `SystemConstants.IMAGE_UPLOAD_DIR = "D:\\tlias\\nginx-1.18.0 dianping\\html\\hmdp\\imgs"`
    - 使用 Windows 绝对路径，不跨平台
    - **建议**：改为配置项，使用相对路径或云存储（OSS/MinIO）

#### 🟢 优化建议

11. **缓存预热机制**
    - `HmDianPingApplicationTests.loadShopDate()` 测试中包含商铺 GEO 数据预热逻辑，但需手动执行
    - **建议**：使用 `@PostConstruct` 或在 `ApplicationRunner` 中自动预热

12. **接口幂等性**
    - 秒杀下单通过 Lua 脚本保证 Redis 层面原子性，但 MQ 消费端 `handleVoucherOrder` 无幂等保护
    - **建议**：增加基于订单 ID 的 Redis 去重标记

13. **缺少 API 文档**
    - 未集成 Swagger/OpenAPI
    - **建议**：引入 SpringDoc 或 Knife4j 自动生成接口文档

14. **缺少参数校验**
    - Controller 层未使用 `@Valid` / `@Validated` 进行 JSR-303 参数校验
    - **建议**：添加 Bean Validation 注解（`@NotBlank`、`@NotNull` 等）

15. **缺少监控与健康检查**
    - 未集成 Actuator
    - **建议**：引入 `spring-boot-starter-actuator` 提供健康检查、Metrics 等运维端点

16. **`tb_sign` 表定义了但签到功能使用 Redis BitMap 实现**
    - 表无用，可考虑删除

17. **BlogComments、UserInfo 等模块仅有空壳**
    - Controller/Service/Mapper 已定义但无业务逻辑
    - **建议**：补充实现或移除未使用的模块

---

## 九、总结

### 项目优点
- 采用了 Redis 缓存穿透/击穿的成熟解决方案（空值缓存+逻辑过期+互斥锁）
- 秒杀场景使用 Lua 脚本 + RabbitMQ 异步解耦，架构合理
- 关注流使用 Redis ZSet 实现推模式 Feed 流，支持滚动分页
- 签到功能利用 Redis BitMap，空间效率高
- 使用 MyBatis-Plus 简化 CRUD 开发

### 主要风险点
- **索引缺失**：高并发下数据库性能瓶颈明显
- **配置安全**：敏感信息明文，无环境隔离
- **异常处理**：全局异常处理单一，线上排错困难
- **硬编码**：文件路径、中间件地址硬编码，部署灵活性差

### 技术栈总结

```
Spring Boot 2.3.12 + MyBatis-Plus 3.4.3
├── MySQL 8.x（主数据库）
├── Redis（缓存/分布式锁/签到/点赞/GEO/Feed流/全局ID）
├── RabbitMQ（秒杀异步下单+死信队列）
├── Redisson（分布式锁，代码中未实际启用）
├── Hutool 5.7.17（工具库）
└── Lombok（代码简化）
```
