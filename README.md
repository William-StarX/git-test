# 外卖点单演示<br>Takeout ordering demo

在本地跑通浏览菜单、加入购物车、提交订单、查询与取消订单。项目包含前端、订单、菜单和库存四个模块，使用 Docker Compose 一起启动。

Run a local flow from browsing the menu and filling a cart to placing, looking up, and cancelling an order. Docker Compose starts the frontend, order, menu, and inventory modules together.

## 快速开始<br>Quick start

准备 Git 和支持 Compose 的 Docker，启动 Linux 容器引擎后执行：

Install Git and Docker with Compose support, start the Linux container engine, and run:

```sh
git clone https://github.com/William-StarX/MultiAgent-test.git
cd MultiAgent-test
docker compose up -d --build --wait
```

服务就绪后，打开 [本地点单页面](http://127.0.0.1:18090/)。Java 编译和依赖服务都由容器处理。

Once the services are ready, open the [local ordering page](http://127.0.0.1:18090/). Containers handle Java compilation and the supporting services.

## 试一次下单<br>Try an order

1. 在菜单中选择菜品，加入购物车并调整数量。

   Choose items from the menu, add them to the cart, and adjust quantities.

2. 点击“提交订单”，保存页面显示的订单号。

   Click “提交订单” (Place order) and keep the order ID shown on the page.

3. 在“查询订单”中输入订单号查看状态；符合条件的订单会显示取消按钮。

   Enter the ID under “查询订单” (Look up order) to check its status. Eligible orders show a cancel button.

也可以直接检查接口。健康接口应返回 `UP`，菜单和订单接口返回 JSON：

You can also check the endpoints directly. The health endpoint should return `UP`; the menu and order endpoints return JSON:

```sh
curl -fsS http://127.0.0.1:18090/actuator/health
curl -fsS http://127.0.0.1:18090/api/menu
curl -fsS http://127.0.0.1:18090/api/orders
```

## 查看状态、更改端口与停止<br>Status, port settings, and shutdown

查看服务状态和日志，或停止当前演示：

Inspect service status and logs, or stop the demo:

```sh
docker compose ps
docker compose logs --tail=100 order-service
docker compose down
```

`docker compose down` 保留数据库和消息队列的数据卷。要更换页面端口，在仓库根目录的 `.env` 中设置 `TAKEOUT_PORT=18091`，再执行启动命令，并访问新端口。数据库和消息队列密码可分别通过 `TAKEOUT_DB_PASSWORD`、`TAKEOUT_MQ_PASSWORD` 设置。

`docker compose down` keeps the database and message-queue volumes. To change the web port, set `TAKEOUT_PORT=18091` in a `.env` file at the repository root, run the startup command again, and visit the new port. Set database and message-queue passwords with `TAKEOUT_DB_PASSWORD` and `TAKEOUT_MQ_PASSWORD`.

## 运行验收<br>Run acceptance checks

安装 Python 3 后，在仓库根目录执行以下命令。脚本建立独立的 Compose 环境，检查下单、重复提交、取消和故障恢复，并将结果写入 `target/acceptance.json`。

With Python 3 installed, run the command from the repository root. The script creates a separate Compose environment, checks ordering, repeated submissions, cancellation, and failure recovery, and writes results to `target/acceptance.json`.

```sh
python scripts/acceptance.py
```

## 项目特点与源码入口<br>Features and source entry points

- **一次启动完整演示。** Nginx 提供页面和统一 API 入口，Compose 同时准备 PostgreSQL、Redis 和 RabbitMQ。

  **Start the whole demo together.** Nginx serves the page and a shared API entry point, while Compose prepares PostgreSQL, Redis, and RabbitMQ.

- **覆盖下单后的恢复流程。** 前端保留未完成的提交草稿并使用幂等键；库存补偿持久化，便于检查重试和服务恢复后的行为。

  **Exercise recovery after an order.** The frontend retains pending submission drafts and uses idempotency keys. Inventory compensation is persisted so retries and service recovery can be checked.

| 入口<br>Entry | 用途<br>Purpose |
|---|---|
| [web-ui](web-ui/) | 点单页面与 Nginx 路由<br>Ordering page and Nginx routing |
| [order-service](order-service/) | 下单、查询与取消<br>Order placement, lookup, and cancellation |
| [menu-service](menu-service/) | 菜单与库存展示<br>Menu and stock display |
| [inventory-service](inventory-service/) | 库存扣减与回补<br>Stock deductions and compensation |
| [compose.yaml](compose.yaml) | 服务、端口和依赖配置<br>Services, ports, and dependencies |
| [验收脚本](scripts/acceptance.py)<br>[Acceptance script](scripts/acceptance.py) | 完整验收流程<br>Acceptance workflow |

单独开发 Java 模块时，使用 JDK 21 和 Maven，例如 `mvn -f order-service/pom.xml package`。Spring Boot 等依赖见 [根 POM](pom.xml)，镜像构建方式见 [Dockerfile](Dockerfile)。

For individual Java modules, use JDK 21 and Maven, for example `mvn -f order-service/pom.xml package`. See the [root POM](pom.xml) for Spring Boot and other dependencies, and the [Dockerfile](Dockerfile) for image builds.

## 注意事项<br>Attention

本项目用于本地演示与验收，默认通过回环地址访问。对外部署时需要配置正式的身份认证、访问权限和凭据。

This project is intended for local demos and acceptance checks, with loopback access by default. External deployment requires appropriate authentication, access controls, and credentials.
