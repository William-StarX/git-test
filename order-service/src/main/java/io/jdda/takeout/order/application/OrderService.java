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
        String hash = hash(request.items());

        // Initial replay of an already committed order.
        OrderResponse existing = findOrder(customerId, idempotencyKey);
        if (existing != null) {
            String storedHash = jdbc.queryForObject(
                    "select request_hash from takeout_order where customer_id = ? and request_key = ?",
                    String.class, customerId, idempotencyKey);
            if (hash.equals(storedHash)) {
                return new CreateResult(existing, false);
            }
            throw OrderProblem.idempotencyConflict();
        }

        // Durably claim the intent in autocommit so concurrent requests converge.
        UUID intentId = UUID.randomUUID();
        jdbc.update("insert into order_intent(id, order_id, customer_id, request_key, request_hash, status, created_at, updated_at) "
                        + "values (?, null, ?, ?, ?, ?, clock_timestamp(), clock_timestamp()) "
                        + "on conflict (customer_id, request_key) do nothing",
                intentId, customerId, idempotencyKey, hash, STATUS_OPEN);

        return tx.execute(status -> createLocked(customerId, idempotencyKey, hash, request));
    }

    private CreateResult createLocked(String customerId, String idempotencyKey, String hash, CreateOrderRequest request) {
        IntentRow intent = jdbc.query(
                "select id, order_id, request_hash, status from order_intent "
                        + "where customer_id = ? and request_key = ? for update",
                INTENT_MAPPER, customerId, idempotencyKey).stream().findFirst()
                .orElseThrow(() -> OrderProblem.serviceUnavailable("intent missing"));

        // Recheck actual customer+key inside the lock.
        OrderResponse existing = findOrder(customerId, idempotencyKey);
        if (existing != null) {
            if (hash.equals(intent.requestHash())) {
                return new CreateResult(existing, false);
            }
            throw OrderProblem.idempotencyConflict();
        }
        if (!hash.equals(intent.requestHash())) {
            throw OrderProblem.idempotencyConflict();
        }
        if (STATUS_EXPIRED.equals(intent.status())) {
            throw OrderProblem.idempotencyConflict();
        }

        UUID orderId = intent.orderId() != null ? intent.orderId() : UUID.randomUUID();
        if (intent.orderId() == null) {
            jdbc.update("update order_intent set order_id = ?, updated_at = clock_timestamp() where id = ?",
                    orderId, intent.id());
        }

        Map<Integer, OrderDependencies.MenuItem> menu = deps.fetchMenu();
        List<OrderItemResponse> lines = new ArrayList<>();
        long total = 0L;
        for (OrderItemRequest item : request.items()) {
            OrderDependencies.MenuItem mi = menu.get(item.menuItemId());
            if (mi == null) {
                throw OrderProblem.menuItemNotFound();
            }
            long lineTotal = mi.priceCents() * item.quantity();
            total += lineTotal;
            lines.add(new OrderItemResponse(mi.menuItemId(), mi.name(), mi.priceCents(), item.quantity()));
        }

        deps.reserve(orderId, request.items());

        jdbc.update("insert into takeout_order(id, customer_id, request_key, request_hash, status, total_cents) "
                        + "values (?, ?, ?, ?, ?, ?)",
                orderId, customerId, idempotencyKey, hash, "CONFIRMED", total);

        for (OrderItemResponse line : lines) {
            jdbc.update("insert into order_line(order_id, menu_item_id, item_name, unit_price_cents, quantity) "
                            + "values (?, ?, ?, ?, ?)",
                    orderId, line.menuItemId(), line.name(), line.unitPriceCents(), line.quantity());
        }

        jdbc.update("insert into outbox_event(event_id, order_id) values (?, ?)", UUID.randomUUID(), orderId);

        jdbc.update("update order_intent set status = ?, updated_at = clock_timestamp() where id = ?",
                STATUS_COMPLETED, intent.id());

        OrderResponse response = findOrder(customerId, idempotencyKey);
        if (response == null) {
            throw OrderProblem.serviceUnavailable("order not visible after commit");
        }
        return new CreateResult(response, true);
    }

    public OrderResponse get(UUID id) {
        OrderResponse order = loadOrder(id);
        if (order == null) {
            throw OrderProblem.orderNotFound();
        }
        return order;
    }

    public OrderResponse cancel(UUID id) {
        Boolean cancelled = tx.execute(status -> {
            OrderRow row = jdbc.query(
                    "select id, customer_id, status from takeout_order where id = ? for update",
                    ORDER_ROW_MAPPER, id).stream().findFirst().orElse(null);
            if (row == null) {
                throw OrderProblem.orderNotFound();
            }
            if ("CANCELLED".equals(row.status())) {
                return false;
            }
            jdbc.update("update takeout_order set status = 'CANCELLED', cancelled_at = clock_timestamp() where id = ?", id);
            jdbc.update("insert into order_release_retry(order_id, attempts, completed, created_at, updated_at) "
                            + "values (?, 0, false, clock_timestamp(), clock_timestamp()) "
                            + "on conflict (order_id) do nothing",
                    id);
            return true;
        });

        if (Boolean.TRUE.equals(cancelled)) {
            try {
                deps.release(id);
                jdbc.update("update order_release_retry set completed = true, updated_at = clock_timestamp() where order_id = ?", id);
            } catch (RuntimeException ignored) {
                // Durable retry row remains; scheduler will retry.
            }
        }
        return get(id);
    }

    private void validate(String idempotencyKey, CreateOrderRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw OrderProblem.badRequest("idempotency key required");
        }
        if (request == null || request.customerId() == null || request.customerId().isBlank()) {
            throw OrderProblem.badRequest("customer id required");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw OrderProblem.badRequest("items required");
        }
        for (OrderItemRequest item : request.items()) {
            if (item == null || item.menuItemId() <= 0 || item.quantity() <= 0) {
                throw OrderProblem.badRequest("invalid item");
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
