package io.jdda.takeout.order.application;

import io.jdda.takeout.OrderModels.CreateOrderRequest;
import io.jdda.takeout.OrderModels.OrderItemRequest;
import io.jdda.takeout.OrderModels.OrderItemResponse;
import io.jdda.takeout.OrderModels.OrderResponse;
import io.jdda.takeout.order.client.OrderDependencies;
import io.jdda.takeout.order.domain.OrderProblem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderService {

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OrderDependencies deps;

    public OrderService(JdbcTemplate jdbc, PlatformTransactionManager txManager, OrderDependencies deps) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.deps = deps;
    }

    public record CreateResult(OrderResponse order, boolean created) {
    }

public CreateResult create(String idempotencyKey, CreateOrderRequest request) {
    validate(idempotencyKey, request);
    String customerId = request.customerId();
    String requestHash = hash(request.items());
    OrderResponse existing = findOrder(customerId, idempotencyKey);
    if (existing != null) {
        return new CreateResult(existing, false);
    }
    UUID intentId = UUID.randomUUID();
    jdbc.update("INSERT INTO order_intent(id, order_id, customer_id, request_key, request_hash, status, created_at) VALUES (?, ?, ?, ?, ?, ?, clock_timestamp()) ON CONFLICT(customer_id, request_key) DO NOTHING",
            intentId, intentId, customerId, idempotencyKey, requestHash, STATUS_OPEN);
    return tx.execute(status -> {
        List<IntentRow> rows = jdbc.query("SELECT id, order_id, request_hash, status FROM order_intent WHERE customer_id = ? AND request_key = ? FOR UPDATE",
                INTENT_MAPPER, customerId, idempotencyKey);
        if (rows.isEmpty()) {
            throw OrderProblem.serviceUnavailable("intent unavailable");
        }
        IntentRow intent = rows.get(0);
        if (!requestHash.equals(intent.requestHash())) {
            throw OrderProblem.idempotencyConflict();
        }
        OrderResponse replay = findOrder(customerId, idempotencyKey);
        if (replay != null) {
            return new CreateResult(replay, false);
        }
        if (!STATUS_OPEN.equals(intent.status())) {
            throw OrderProblem.idempotencyConflict();
        }
        UUID orderId = intent.orderId();
        Map<Integer, OrderDependencies.MenuItem> menu = deps.fetchMenu();
        List<OrderItemResponse> snapshots = new ArrayList<>();
        long total = 0L;
        for (OrderItemRequest item : request.items()) {
            OrderDependencies.MenuItem menuItem = menu.get(item.menuItemId());
            if (menuItem == null) {
                throw OrderProblem.menuItemNotFound();
            }
            long lineTotal = menuItem.priceCents() * item.quantity();
            total += lineTotal;
            snapshots.add(new OrderItemResponse(item.menuItemId(), menuItem.name(), menuItem.priceCents(), item.quantity()));
        }
        deps.reserve(orderId, request.items());
        jdbc.update("INSERT INTO takeout_order(id, customer_id, request_key, request_hash, status, total_cents) VALUES (?, ?, ?, ?, 'PENDING', ?)",
                orderId, customerId, idempotencyKey, requestHash, total);
        for (OrderItemResponse snapshot : snapshots) {
            jdbc.update("INSERT INTO order_line(order_id, menu_item_id, item_name, unit_price_cents, quantity) VALUES (?, ?, ?, ?, ?)",
                    orderId, snapshot.menuItemId(), snapshot.name(), snapshot.priceCents(), snapshot.quantity());
        }
        jdbc.update("INSERT INTO outbox_event(event_id, order_id) VALUES (?, ?)", UUID.randomUUID(), orderId);
        jdbc.update("UPDATE order_intent SET status = ? WHERE id = ?", STATUS_COMPLETED, intent.id());
        OrderResponse response = new OrderResponse(orderId, customerId, "PENDING", total, snapshots);
        return new CreateResult(response, true);
    });
}
    public OrderResponse get(UUID id) {
        OrderResponse order = loadOrder(id);
        if (order == null) {
            throw OrderProblem.orderNotFound();
        }
        return order;
    }

public OrderResponse cancel(UUID id) {
    Boolean firstCancel = tx.execute(status -> {
        List<String> statuses = jdbc.queryForList("SELECT status FROM takeout_order WHERE id = ? FOR UPDATE", String.class, id);
        if (statuses.isEmpty()) {
            throw OrderProblem.orderNotFound();
        }
        String current = statuses.get(0);
        if ("CANCELLED".equals(current)) {
            return Boolean.FALSE;
        }
        jdbc.update("UPDATE takeout_order SET status = 'CANCELLED', updated_at = clock_timestamp() WHERE id = ?", id);
        jdbc.update("INSERT INTO order_release_retry(order_id, completed, created_at, updated_at) VALUES (?, FALSE, clock_timestamp(), clock_timestamp()) ON CONFLICT(order_id) DO NOTHING", id);
        return Boolean.TRUE;
    });
    if (Boolean.TRUE.equals(firstCancel)) {
        try {
            release(id);
        } catch (RuntimeException ignored) {
        }
    }
    return get(id);
}

private void release(UUID orderId) {
    deps.release(orderId);
    jdbc.update("UPDATE order_release_retry SET completed = TRUE, updated_at = clock_timestamp() WHERE order_id = ?", orderId);
}

@org.springframework.scheduling.annotation.Scheduled(fixedDelay = 2000)
public void retryReleases() {
    List<UUID> pending = jdbc.queryForList("SELECT order_id FROM order_release_retry WHERE completed = FALSE ORDER BY created_at LIMIT 10", UUID.class);
    for (UUID orderId : pending) {
        try {
            release(orderId);
        } catch (RuntimeException ignored) {
        }
    }
}

@org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
public void abandonStaleIntents() {
    tx.execute(status -> {
        List<IntentRow> rows = jdbc.query("SELECT id, order_id, request_hash, status FROM order_intent WHERE status = ? AND created_at < clock_timestamp() - INTERVAL '10 minutes' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                INTENT_MAPPER, STATUS_OPEN);
        if (rows.isEmpty()) {
            return null;
        }
        IntentRow intent = rows.get(0);
        List<UUID> existing = jdbc.queryForList("SELECT id FROM takeout_order WHERE id = ?", UUID.class, intent.orderId());
        if (!existing.isEmpty()) {
            return null;
        }
        deps.release(intent.orderId());
        jdbc.update("UPDATE order_intent SET status = ? WHERE id = ?", STATUS_EXPIRED, intent.id());
        return null;
    });
}
private void validate(String idempotencyKey, CreateOrderRequest request) {
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._-]{1,128}")) {
        throw OrderProblem.badRequest("invalid idempotency key");
    }
    if (request == null) {
        throw OrderProblem.badRequest("request is required");
    }
    String customerId = request.customerId();
    if (customerId == null || !customerId.matches("[A-Za-z0-9_-]{1,64}")) {
        throw OrderProblem.badRequest("invalid customer id");
    }
    List<OrderItemRequest> items = request.items();
    if (items == null || items.isEmpty() || items.size() > 20) {
        throw OrderProblem.badRequest("items must contain between 1 and 20 entries");
    }
    java.util.Set<Integer> seen = new java.util.HashSet<>();
    for (OrderItemRequest item : items) {
        if (item == null) {
            throw OrderProblem.badRequest("item is required");
        }
        int menuItemId = item.menuItemId();
        if (menuItemId <= 0) {
            throw OrderProblem.badRequest("invalid menu item id");
        }
        if (!seen.add(menuItemId)) {
            throw OrderProblem.badRequest("duplicate menu item id");
        }
        int quantity = item.quantity();
        if (quantity < 1 || quantity > 99) {
            throw OrderProblem.badRequest("invalid quantity");
        }
    }
}
    private static String hash(List<OrderItemRequest> items) {
        List<OrderItemRequest> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(OrderItemRequest::menuItemId));
        StringBuilder sb = new StringBuilder();
        for (OrderItemRequest item : sorted) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(item.menuItemId()).append(':').append(item.quantity());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private OrderResponse findOrder(String customerId, String requestKey) {
        List<UUID> ids = jdbc.query(
                "select id from takeout_order where customer_id = ? and request_key = ?",
                (rs, i) -> rs.getObject("id", UUID.class), customerId, requestKey);
        return ids.isEmpty() ? null : loadOrder(ids.get(0));
    }

    private OrderResponse loadOrder(UUID id) {
        List<OrderRow> rows = jdbc.query(
                "select id, customer_id, status, total_cents, created_at, confirmed_at, cancelled_at "
                        + "from takeout_order where id = ?",
                ORDER_ROW_MAPPER, id);
        if (rows.isEmpty()) {
            return null;
        }
        OrderRow row = rows.get(0);
        List<OrderItemResponse> items = jdbc.query(
                "select menu_item_id, item_name, unit_price_cents, quantity from order_line where order_id = ? order by menu_item_id",
                (rs, i) -> new OrderItemResponse(
                        rs.getInt("menu_item_id"),
                        rs.getString("item_name"),
                        rs.getLong("unit_price_cents"),
                        rs.getInt("quantity")),
                id);
        return new OrderResponse(row.id(), row.customerId(), row.status(), row.totalCents(), items,
                row.createdAt(), row.confirmedAt(), row.cancelledAt());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        if (odt != null) {
            return odt.toInstant();
        }
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private record OrderRow(UUID id, String customerId, String status, long totalCents,
                            Instant createdAt, Instant confirmedAt, Instant cancelledAt) {
    }

    private record IntentRow(UUID id, UUID orderId, String requestHash, String status) {
    }

    private static final RowMapper<OrderRow> ORDER_ROW_MAPPER = (rs, i) -> new OrderRow(
            rs.getObject("id", UUID.class),
            rs.getString("customer_id"),
            rs.getString("status"),
            rs.getLong("total_cents"),
            instant(rs, "created_at"),
            instant(rs, "confirmed_at"),
            instant(rs, "cancelled_at"));

    private static final RowMapper<IntentRow> INTENT_MAPPER = (rs, i) -> new IntentRow(
            rs.getObject("id", UUID.class),
            rs.getObject("order_id", UUID.class),
            rs.getString("request_hash"),
            rs.getString("status"));
}
