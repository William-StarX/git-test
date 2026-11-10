# 外卖点单平台（takeout-platform）

本仓库由四个相互独立的模块组成，通过根 Maven 聚合器与 Docker Compose 统一打包和部署。

## 模块与打包

| 模块 | 说明 | 打包方式 |
| --- | --- | --- |
| `order-service` | 订单服务，负责下单、订单查询与库存补偿编排 | Maven（Spring Boot 可执行 jar） |
| `inventory-service` | 库存服务，负责库存扣减与回补 | Maven（Spring Boot 可执行 jar） |
| `menu-service` | 菜单服务，负责菜品与库存展示 | Maven（Spring Boot 可执行 jar） |
| `web-ui` | 前端静态页面与反向代理 | 独立 Nginx 模块，不参与 Maven 构建 |

根 `pom.xml` 为 `io.jdda:takeout-platform:0.1.0`，`packaging` 为 `pom`，继承 Spring Boot 父工程 `3.5.16`，`java.version` 为 `21`，`flyway.version` 为 `11.20.3`，聚合 `order-service`、`inventory-service`、`menu-service` 三个模块。`web-ui` 为独立的 Nginx 模块，Maven 不依赖它。

每个 Java 模块都可以单独构建：

```bash
mvn -f order-service/pom.xml package
mvn -f inventory-service/pom.xml package
mvn -f menu-service/pom.xml package
```

产物为各模块 `target/<module>-0.1.0.jar`。

## 镜像构建

根目录 `Dockerfile` 为多阶段构建，通过 `ARG MODULE` 选择模块：

```bash
docker build --build-arg MODULE=order-service -t takeout-orders/order-service:0.1.0 .
docker build --build-arg MODULE=inventory-service -t takeout-orders/inventory-service:0.1.0 .
docker build --build-arg MODULE=menu-service -t takeout-orders/menu-service:0.1.0 .
docker build -t takeout-orders/web-ui:0.1.0 ./web-ui
```

构建阶段使用固定摘要的 `maven:3.9.11-eclipse-temurin-21`，运行阶段使用固定摘要的 `eclipse-temurin:21-jre`，以非 root 用户 `10001` 运行。

`web-ui/Dockerfile` 基于官方 `nginx:1.28-alpine`，将 `nginx.conf` 复制为 `conf.d/default.conf`，并将 `index.html`、`styles.css`、`app.js` 复制到 html 目录，监听 `8080`。

## 网关路由

Nginx 仅提供同源访问，并设置安全的 CSP 与安全响应头，不使用内联 JS：

- `/` 返回静态资源；
- 精确 `/api/menu` 转发到 `menu-service:8080`；
- `/api/menu/{id}/stock` 转发到 `inventory-service:8080`；
- `/api/orders` 及其子路径转发到 `order-service:8080`；
- 精确 `/actuator/health` 转发到 `order-service`；
- `/internal` 与未识别的 `/api` 路由返回 404。

代理保持原始 URI 不变，并透传 `X-Cache`。使用 Docker 内置 DNS 解析器，服务端点被替换后可重新解析。

## 组合部署

`compose.yaml` 项目名为 `takeout-orders`，包含 `app`、`order-service`、`inventory-service`、`menu-service`、`postgres`、`redis`、`rabbitmq`。仅 `app` 暴露宿主机端口 `127.0.0.1:${TAKEOUT_PORT:-18090}:8080`（端口设为 0 也可用），三个 Java 服务仅在内部 `8080` 通信。

- 数据库：主机 `postgres`，库/用户 `takeout`，密码 `TAKEOUT_DB_PASSWORD`，默认 `takeout-local-db`；
- 消息队列：主机 `rabbitmq`，主机名 `rabbitmq`，用户 `takeout`，密码 `TAKEOUT_MQ_PASSWORD`，默认 `takeout-local-mq`；
- 缓存：主机 `redis`；
- 订单服务通过 `MENU_BASE_URL=http://menu-service:8080` 与 `INVENTORY_BASE_URL=http://inventory-service:8080` 访问下游。

数据卷 `pg-data` 挂载到 `/var/lib/postgresql`，`mq-data` 挂载到 `/var/lib/rabbitmq`，不会删除数据。

健康检查：Postgres 使用 `pg_isready`，Redis 使用 `redis-cli ping`，RabbitMQ 使用 `rabbitmq-diagnostics -q ping`。订单服务在 Postgres 与 RabbitMQ 健康后启动；菜单与库存服务在订单服务 `/actuator/health` 为 UP 后启动，以保证其一次性迁移所需的 V1 已存在；`app` 在三个服务健康后启动。服务健康检查使用 `curl`（Temurin Ubuntu 基础镜像自带），重试次数充足。

## 快速开始

```bash
docker compose up -d --build
```

访问 `http://127.0.0.1:18090/`（或自定义 `TAKEOUT_PORT`）。

## 库存补偿

库存扣减与回补通过消息队列异步完成，补偿逻辑具备持久化能力，服务重启后仍可继续处理未完成的补偿，避免库存长期不一致。

## 本地演示范围

本项目面向本地演示与验收，使用 Compose 在单机拉起全部四个模块，不涉及生产环境的身份与权限能力。

## 验收

保留的受保护验收入口 `scripts/acceptance.py` 与 `.github/workflows/acceptance.yml`、`compose.acceptance.yaml` 保持不变。验收脚本通过 Compose 的 `app` 服务 `8080` 端口访问，使用 `exec postgres` 操作数据库，并会停止 `redis` 与 `rabbitmq` 以验证补偿行为，脚本无需修改即可运行。

也可用 curl 做基础验证：

```bash
curl -fsS http://127.0.0.1:18090/actuator/health
curl -fsS http://127.0.0.1:18090/api/menu
curl -fsS http://127.0.0.1:18090/api/orders
```
