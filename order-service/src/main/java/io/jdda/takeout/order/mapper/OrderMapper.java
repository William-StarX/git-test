package io.jdda.takeout.order.mapper;

import io.jdda.takeout.order.domain.OrderModels.OrderItemResponse;
import io.jdda.takeout.order.domain.OrderModels.OrderResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OrderMapper {
    private final JdbcTemplate jdbc;

    public OrderMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertIntentIfAbsent(UUID intentId, String customerId, String idempotencyKey, String requestHash, String status) {
        jdbc.update("INSERT INTO order_intent(id, order_id, customer_id, request_key, request_hash, status, created_at) VALUES (?, ?, ?, ?, ?, ?, clock_timestamp()) ON CONFLICT(customer_id, request_key) DO NOTHING",
            intentId, intentId, customerId, idempotencyKey, requestHash, status);
    }

    public List<IntentRow> lockIntentByCustomerAndKey(String customerId, String idempotencyKey) {
        return jdbc.query("SELECT id, order_id, request_hash, status FROM order_intent WHERE customer_id = ? AND request_key = ? FOR UPDATE",
                INTENT_MAPPER, customerId, idempotencyKey);
    }

    public void insertPendingOrder(UUID orderId, String customerId, String idempotencyKey, String requestHash, long total) {
        jdbc.update("INSERT INTO takeout_order(id, customer_id, request_key, request_hash, status, total_cents) VALUES (?, ?, ?, ?, 'PENDING', ?)",
                orderId, customerId, idempotencyKey, requestHash, total);
    }

    public void insertOrderLine(UUID orderId, OrderItemResponse snapshot) {
        jdbc.update("INSERT INTO order_line(order_id, menu_item_id, item_name, unit_price_cents, quantity) VALUES (?, ?, ?, ?, ?)",
                    orderId, snapshot.menuItemId(), snapshot.name(), snapshot.unitPriceCents(), snapshot.quantity());
    }

    public void insertOrderPlacedOutboxEvent(UUID eventId, UUID orderId) {
        jdbc.update("INSERT INTO outbox_event(event_id, order_id) VALUES (?, ?)", eventId, orderId);
    }

    public void completeIntent(UUID intentId) {
        jdbc.update("UPDATE order_intent SET status = ? WHERE id = ?", "COMPLETED", intentId);
    }

    public List<String> lockOrderStatus(UUID id) {
        return jdbc.queryForList("SELECT status FROM takeout_order WHERE id = ? FOR UPDATE", String.class, id);
    }

    public void markOrderCancelled(UUID id) {
        jdbc.update("UPDATE takeout_order SET status = 'CANCELLED', cancelled_at = clock_timestamp() WHERE id = ?", id);
    }

    public void enqueueStockReleaseIfAbsent(UUID id) {
        jdbc.update("INSERT INTO order_release_retry(order_id, completed, created_at, updated_at) VALUES (?, FALSE, clock_timestamp(), clock_timestamp()) ON CONFLICT(order_id) DO NOTHING", id);
    }

    public void markStockReleaseCompleted(UUID orderId) {
        jdbc.update("UPDATE order_release_retry SET completed = TRUE, updated_at = clock_timestamp() WHERE order_id = ?", orderId);
    }

    public List<UUID> listPendingStockReleases() {
        return jdbc.queryForList("SELECT order_id FROM order_release_retry WHERE completed = FALSE ORDER BY created_at LIMIT 10", UUID.class);
    }

    public List<IntentRow> lockStaleIntentSkipLocked(String status) {
        return jdbc.query("SELECT id, order_id, request_hash, status FROM order_intent WHERE status = ? AND created_at < clock_timestamp() - INTERVAL '10 minutes' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                INTENT_MAPPER, status);
    }

    public List<UUID> findExistingOrderId(UUID orderId) {
        return jdbc.queryForList("SELECT id FROM takeout_order WHERE id = ?", UUID.class, orderId);
    }

    public void expireIntent(UUID intentId) {
        jdbc.update("UPDATE order_intent SET status = ? WHERE id = ?", "EXPIRED", intentId);
    }

    public List<OrderKey> findOrderKeyByCustomerAndRequest(String customerId, String requestKey) {
        return jdbc.query(
                "SELECT id, request_hash FROM takeout_order WHERE customer_id = ? AND request_key = ?",
                (rs, rowNum) -> new OrderKey(rs.getObject("id", UUID.class), rs.getString("request_hash")),
                customerId, requestKey);
    }

    public OrderResponse loadOrder(UUID id) {
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

    public record IntentRow(UUID id, UUID orderId, String requestHash, String status) {
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

    public record OrderKey(UUID id, String requestHash) {}
}
