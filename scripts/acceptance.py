"""Run the order acceptance flow with a fresh, isolated Compose project."""

import base64
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener
from uuid import UUID, uuid4


ROOT = Path(__file__).resolve().parents[1]
PROJECT = "takeout-accept-" + uuid4().hex[:12]
COMPOSE = ["docker", "compose", "-p", PROJECT, "-f", "compose.yaml", "-f", "compose.acceptance.yaml"]
ENV = os.environ | {"TAKEOUT_PORT": "0", "TAKEOUT_DB_PASSWORD": uuid4().hex, "TAKEOUT_MQ_PASSWORD": uuid4().hex}
HTTP = build_opener(ProxyHandler({}))


def compose(*args, capture=True):
    return subprocess.run(
        COMPOSE + list(args), cwd=ROOT, env=ENV, check=True, timeout=480,
        text=True, encoding="utf-8", errors="replace", stdout=subprocess.PIPE if capture else None,
    ).stdout


def local_url(service, port):
    address = compose("port", service, str(port)).strip()
    assert address.startswith("127.0.0.1:"), address
    return "http://" + address


def http(url, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    request = Request(url, data, headers or {})
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        reply = HTTP.open(request, timeout=10)
    except HTTPError as error:
        reply = error
    with reply:
        return reply.status, json.load(reply), reply.headers


def wait_for(check, description, timeout=70):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(.4)
    raise AssertionError("Timed out waiting for " + description)


def sql(statement):
    return compose("exec", "-T", "postgres", "psql", "-U", "takeout", "-d", "takeout",
                   "-XAt", "-v", "ON_ERROR_STOP=1", "-c", statement).strip()


def order(api, customer, key, *lines):
    body = {"customerId": customer, "items": [{"menuItemId": item, "quantity": quantity} for item, quantity in lines]}
    return http(api + "/api/orders", body, {"Idempotency-Key": key})


def stock(api, menu_id):
    code, body, _ = http(f"{api}/api/menu/{menu_id}/stock")
    assert code == 200
    return body["available"]


def order_status(api, order_id):
    code, body, _ = http(f"{api}/api/orders/{order_id}")
    assert code == 200
    return body


def management(api, path, body=None):
    credential = base64.b64encode(("takeout:" + ENV["TAKEOUT_MQ_PASSWORD"]).encode()).decode()
    code, result, _ = http(api + "/api" + path, body, {"Authorization": "Basic " + credential})
    assert code == 200, (path, code, result)
    return result


def acceptance():
    api = local_url("app", 8080)
    broker_api = local_url("rabbitmq", 15672)
    wait_for(lambda: http(api + "/actuator/health")[1].get("status") == "UP", "the application and dependencies")

    cold = http(api + "/api/menu")
    warm = http(api + "/api/menu")
    assert cold[0] == warm[0] == 200 and len(cold[1]) == 3 and cold[1] == warm[1]
    assert cold[2]["X-Cache"] == "MISS" and warm[2]["X-Cache"] == "HIT"
    ttl = int(compose("exec", "-T", "redis", "redis-cli", "TTL", "takeout:menu:v1"))
    assert 0 < ttl <= 120

    with ThreadPoolExecutor(max_workers=32) as pool:
        results = list(pool.map(lambda i: order(api, f"customer-{i}", "sale", (1, 1)), range(40)))
    sold = [body for code, body, _ in results if code == 201]
    rejected = [body for code, body, _ in results if code == 409]
    assert len(sold) == 10 and len(rejected) == 30 and all(body["code"] == "INSUFFICIENT_STOCK" for body in rejected)
    assert stock(api, 1) == 0

    with ThreadPoolExecutor(max_workers=16) as pool:
        repeats = list(pool.map(lambda _: order(api, "repeat-customer", "same-key", (2, 1)), range(20)))
    assert sorted(code for code, _, _ in repeats) == [200] * 19 + [201]
    assert len({body["id"] for _, body, _ in repeats}) == 1 and stock(api, 2) == 29
    assert sql("SELECT count(*) FROM takeout_order WHERE customer_id='repeat-customer'") == "1"
    assert order(api, "repeat-customer", "same-key", (2, 2))[1]["code"] == "IDEMPOTENCY_CONFLICT"
    assert order(api, "invalid", "zero", (2, 0))[0] == 400
    assert order(api, "rollback", "two-items", (2, 1), (3, 99))[1]["code"] == "INSUFFICIENT_STOCK"
    assert stock(api, 2) == 29 and stock(api, 3) == 20

    wait_for(lambda: sql("SELECT count(*) FROM takeout_order WHERE status='CONFIRMED'") == "11",
             "all eleven order confirmations before the broker outage")
    first = sold[0]["id"]
    assert http(f"{api}/api/orders/{first}/cancel", {})[1]["status"] == "CANCELLED"
    assert http(f"{api}/api/orders/{first}/cancel", {})[1]["status"] == "CANCELLED"
    assert stock(api, 1) == 1

    compose("stop", "rabbitmq", capture=False)
    code, pending, _ = order(api, "outage-customer", "durable", (2, 2))
    assert code == 201 and pending["status"] == "PENDING"
    pending_id = str(UUID(pending["id"]))
    assert sql(f"SELECT count(*) FROM outbox_event WHERE order_id='{pending_id}' AND published_at IS NULL") == "1"
    compose("start", "rabbitmq", capture=False)
    confirmed = wait_for(lambda: (item if (item := order_status(api, pending_id))["status"] == "CONFIRMED" else None),
                         "confirmation after the broker restarted")
    broker_api = local_url("rabbitmq", 15672)
    wait_for(lambda: sql(f"SELECT count(*) FROM outbox_event WHERE order_id='{pending_id}' AND published_at IS NOT NULL") == "1",
             "outbox publish confirmation")

    event = str(UUID(sql(f"SELECT event_id FROM outbox_event WHERE order_id='{pending_id}'")))
    queue = "/queues/%2F/takeout.order.placed"
    ack_before = wait_for(lambda: management(broker_api, queue).get("message_stats", {}).get("ack", 0),
                          "the recovered event acknowledgement")
    for _ in range(2):
        published = management(broker_api, "/exchanges/%2F/takeout.orders/publish", {
            "properties": {"content_type": "text/plain", "delivery_mode": 2, "message_id": event},
            "routing_key": "order.placed", "payload": json.dumps({"eventId": event, "orderId": pending_id}),
            "payload_encoding": "string",
        })
        assert published["routed"]
    wait_for(lambda: management(broker_api, queue).get("message_stats", {}).get("ack", 0) >= ack_before + 2,
             "broker acknowledgements for both duplicate messages")
    assert sql(f"SELECT count(*) FROM processed_event WHERE event_id='{event}'") == "1"
    assert order_status(api, pending_id)["confirmedAt"] == confirmed["confirmedAt"]
    assert management(broker_api, "/queues/%2F/takeout.order.dead")["messages"] == 0
    assert order_status(api, first)["status"] == "CANCELLED"

    compose("stop", "redis", capture=False)
    fallback = http(api + "/api/menu")
    assert fallback[0] == 200 and fallback[1] == cold[1] and fallback[2]["X-Cache"] == "BYPASS"
    return {
        "status": "PASSED", "project": PROJECT, "parallelOrders": 40, "sold": len(sold), "stockConflicts": len(rejected),
        "parallelReplays": len(repeats), "ordersForRepeatedKey": 1, "menuCache": ["MISS", "HIT", "BYPASS"],
        "cacheTtlSeconds": ttl, "transactionRollback": True, "repeatCancellationRefundedOnce": True,
        "confirmedAfterBrokerRestart": True, "duplicateDeliveriesAcknowledged": 2, "processedEventRows": 1,
    }


if __name__ == "__main__":
    result = None
    try:
        compose("up", "-d", "--build", capture=False)
        result = acceptance()
    except Exception:
        compose("logs", "--tail", "70", "app", capture=False)
        raise
    finally:
        compose("down", "-v", "--remove-orphans", capture=False)
    (ROOT / "target").mkdir(exist_ok=True)
    (ROOT / "target" / "acceptance.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2), flush=True)
