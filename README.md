# 外卖订单服务

基于 Java 21、Spring Boot、PostgreSQL、Redis 和 RabbitMQ 的下单服务。提供菜单查询、实时库存、创建订单、查询订单和取消订单。Flyway 初始化数据库与演示菜单。

## 启动

安装带 Compose 的 Docker 后，从仓库根目录运行。

```powershell
docker compose up -d --build
Invoke-RestMethod 'http://127.0.0.1:18090/actuator/health'
Invoke-RestMethod 'http://127.0.0.1:18090/api/menu'
```

应用默认只发布到本机 `127.0.0.1:18090`。PostgreSQL、Redis 和 RabbitMQ 通过 Compose 内部网络访问。可以用 `TAKEOUT_PORT` 更改本机端口。演示环境使用本地数据库和消息队列凭据。

## 下单流程

```powershell
$api = 'http://127.0.0.1:18090/api'
$key = [guid]::NewGuid().ToString('N')
$order = Invoke-RestMethod -Method Post "$api/orders" `
    -Headers @{ 'Idempotency-Key' = $key } -ContentType 'application/json' `
    -Body '{"customerId":"customer-001","items":[{"menuItemId":1,"quantity":1}]}'
Invoke-RestMethod "$api/orders/$($order.id)"
Invoke-RestMethod "$api/menu/1/stock"
```

| 请求 | 行为 |
| --- | --- |
| `GET /api/menu` | 查询菜品和价格。响应头 `X-Cache` 标识 `MISS`、`HIT` 或 `BYPASS` |
| `GET /api/menu/{id}/stock` | 从 PostgreSQL 读取实时可用库存 |
| `POST /api/orders` | 新订单返回 `201`。同一客户以相同键和相同商品重试返回原订单和 `200`。键被用于其他请求或库存不足时返回 `409` |
| `GET /api/orders/{id}` | 查询订单、价格快照和当前状态 |
| `POST /api/orders/{id}/cancel` | 取消订单并归还库存。重复取消返回同一个结果 |

价格和总价以分表示。提交后的订单状态为 `PENDING`。消费者收到事件后确认订单，取消优先保留 `CANCELLED` 状态。

## 数据与消息

- 订单、库存和待发事件一起提交。PostgreSQL 条件更新保证可用库存充足才扣减。多商品按菜品 ID 顺序加锁。
- Redis 缓存菜单和价格 120 秒。Redis 不可用时查询数据库。库存扣减和查询均使用数据库中的值。
- Outbox 中继收到 RabbitMQ 的发布确认后标记事件。发布重试可能产生重复消息。消费者在同一事务内按事件 ID 去重并确认订单。
- 每次启动使用 Flyway 迁移。Docker 镜像与 GitHub Actions 依赖使用已核对的摘要或提交。

本地验收服务以客户标识演示幂等。对外部署需要接入用户身份、订单归属校验、独立凭据和传输加密。

## 验收

```powershell
python -u scripts/acceptance.py
```

脚本创建独立 Compose 项目并分配临时端口，完成后清理测试容器和数据卷。验收覆盖 40 个并发请求争抢 10 份库存、20 次同键请求、同键不同请求冲突、多商品事务回滚、重复取消、Redis 命中与降级、RabbitMQ 停启恢复和重复投递。结果保存在 `target/acceptance.json`，GitHub Actions 会上传同名验收产物。

实现采用 [Spring Boot AMQP](https://docs.spring.io/spring-boot/3.5/reference/messaging/amqp.html)、[RabbitMQ 发布确认](https://www.rabbitmq.com/docs/confirms) 和 [PostgreSQL 行锁](https://www.postgresql.org/docs/current/sql-select.html)。
