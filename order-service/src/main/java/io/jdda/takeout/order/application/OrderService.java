package io.jdda.takeout.order.application;

import io.jdda.takeout.OrderModels.CreateOrderRequest;
import io.jdda.takeout.OrderModels.OrderItemRequest;
import io.jdda.takeout.OrderModels.OrderItemResponse;
import io.jdda.takeout.OrderModels.OrderResponse;
import io.jdda.takeout.order.client.OrderDependencies;
import io.jdda.takeout.order.client.OrderDependencies.MenuItem;
import io.jdda.takeout.order.domain.OrderProblem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Order application service. Persists a durable order intent before reserving stock,
 * then commits the order atomically. Compensation is idempotent and retried by schedulers.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private static final String INTENT_OPEN = "OPEN";
    private static final String INTENT_COMPLETED = "COMPLETED";
    private static final String INTENT_EXPIRED = "EXPIRED";

    private static final long STALE_INTENT_MILLIS = 10L * 60L * 1000L;

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

    // ------------------------------------------------------------------ create

    public CreateResult create(String idempotencyKey, CreateOrderRequest request) {
        String customerId = request.customerId();
        List<OrderItemRequest> items = normalize(request.items());
        String hash = canonicalHash(items);

        // 1. Historic order replay first (idempotent).
        OrderResponse existing = findExistingOrder(customerId, idempotencyKey, hash);
        if (existing != null) {
            return new CreateResult(existing, false);
        }

        // 2. Persist durable intent in its own committed transaction.
        UUID orderId = UUID.randomUUID();
        UUID intentId = persistIntent(orderId, customerId, idempotencyKey, hash);
        if (intentId == null) {
            // Lost race: another request created the intent. Replay or conflict.
            OrderResponse raced = findExistingOrder(customerId, idempotencyKey, hash);
            if (raced != null) {
                return new CreateResult(raced, false);
            }
            throw OrderProblem.idempotencyConflict();
        }

        // 3. Reserve + commit order atomically.
        try {
            OrderResponse response = tx.execute(status -> reserveAndCommit(intentId, orderId, customerId, hash, items));
            return new CreateResult(response, true);
        } catch (RuntimeException ex) {
            // Compensation: release reservation for this stable UUID, mark intent expired.
            compensate(intentId, orderId);
            throw ex;
        }
    }

    private OrderResponse reserveAndCommit(UUID intentId, UUID orderId, String customerId, String hash,
                                           List<OrderItemRequest> items) {
        // Lock intent row and validate hash.
        List<Map<String, Object>> intentRows = jdbc.queryForList(
                "SELECT status, request_hash FROM order_intent WHERE id = ? FOR UPDATE", intentId);
        if (intentRows.isEmpty()) {
            throw OrderProblem.idempotencyConflict();
        }
        Map<String, Object> intent = intentRows.get(0);
        String intentStatus = (String) intent.get("status");
        String intentHash = (String) intent.get("request_hash");
        if (!INTENT_OPEN.equals(intentStatus) || !hash.equals(intentHash)) {
            throw OrderProblem.idempotencyConflict();
        }

        // Recheck existing order (defensive).
        OrderResponse existing = findExistingOrder(customerId, null, hash);
        if (existing != null) {
            markIntent(intentId, INTENT_COMPLETED);
            return existing;
        }

        // Resolve catalog before reserving.
        Map<Integer, MenuItem> catalog = deps.fetchMenu();
        List<OrderItemResponse> resolved = new ArrayList<>();
        long total = 0L;
        for (OrderItemRequest item : items) {
            MenuItem menuItem = catalog.get(item.menuItemId());
            if (menuItem == null) {
                throw OrderProblem.menuItemNotFound();
            }
            resolved.add(new OrderItemResponse(item.menuItemId(), menuItem.name(), menuItem.priceCents(), item.quantity()));
            total += menuItem.priceCents() * item.quantity();
        }

        // Reserve by stable UUID.
        deps.reserve(orderId, items);

        // Write PENDING order, price snapshots, outbox.
        Instant now = Instant.now();
        jdbc.update("INSERT INTO takeout_order (id, customer_id, request_key, request_hash, status, total_cents, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                orderId, customerId, null, hash, STATUS_PENDING, total, Timestamp.from(now));
        for (OrderItemResponse item : resolved) {
            jdbc.update("INSERT INTO order_line (order_id, menu_item_id, item_name, unit_price_cents, quantity) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    orderId, item.menuItemId(), item.name(), item.unitPriceCents(), item.quantity());
        }
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox_event (event_id, order_id, created_at) VALUES (?, ?, ?)",
                eventId, orderId, Timestamp.from(now));

        markIntent(intentId, INTENT_COMPLETED);

        return new OrderResponse(orderId, customerId, STATUS_PENDING, total, resolved, now, null, null);
    }

    private UUID persistIntent(UUID orderId, String customerId, String idempotencyKey, String hash) {
        UUID intentId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO order_intent (id, order_id, customer_id, request_key, request_hash, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    intentId, orderId, customerId, idempotencyKey, hash, INTENT_OPEN, Timestamp.from(Instant.now()));
            return intentId;
        } catch (DuplicateKeyException ex) {
            return null;
        }
    }

    private void markIntent(UUID intentId, String status) {
        jdbc.update("UPDATE order_intent SET status = ?, updated_at = ? WHERE id = ?",
                status, Timestamp.from(Instant.now()), intentId);
    }

    private void compensate(UUID intentId, UUID orderId) {
        try {
            deps.release(orderId);
        } catch (RuntimeException ex) {
            log.warn("compensation release failed for order {}: {}", orderId, ex.toString());
        }
        try {
            jdbc.update("UPDATE order_intent SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                    INTENT_EXPIRED, Timestamp.from(Instant.now()), intentId, INTENT_OPEN);
        } catch (RuntimeException ex) {
            log.warn("failed to mark intent {} expired: {}", intentId, ex.toString());
        }
    }

    // ------------------------------------------------------------------ read

    public OrderResponse get(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, customer_id, status, total_cents, created_at, confirmed_at, cancelled_at "
                        + "FROM takeout_order WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw OrderProblem.orderNotFound();
        }
        return toResponse(rows.get(0));
    }

    // ------------------------------------------------------------------ cancel

    public OrderResponse cancel(UUID id) {
        OrderResponse cancelled = tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, customer_id, status, total_cents, created_at, confirmed_at, cancelled_at "
                            + "FROM takeout_order WHERE id = ? FOR UPDATE", id);
            if (rows.isEmpty()) {
                throw OrderProblem.orderNotFound();
            }
            Map<String, Object> row = rows.get(0);
            String current = (String) row.get("status");
            if (STATUS_CANCELLED.equals(current)) {
                return toResponse(row);
            }
            Instant now = Instant.now();
            jdbc.update("UPDATE takeout_order SET status = ?, cancelled_at = ? WHERE id = ?",
                    STATUS_CANCELLED, Timestamp.from(now), id);
            jdbc.update("INSERT INTO order_release_retry (order_id, attempts, completed, created_at) "
                            + "VALUES (?, 0, FALSE, ?) ON CONFLICT (order_id) DO NOTHING",
                    id, Timestamp.from(now));
            row.put("status", STATUS_CANCELLED);
            row.put("cancelled_at", Timestamp.from(now));
            return toResponse(row);
        });

        // Attempt remote release after commit.
        attemptRelease(id);
        return cancelled;
    }

    private void attemptRelease(UUID orderId) {
        try {
            deps.release(orderId);
            jdbc.update("UPDATE order_release_retry SET completed = TRUE, updated_at = ? WHERE order_id = ?",
                    Timestamp.from(Instant.now()), orderId);
        } catch (RuntimeException ex) {
            log.warn("release retry pending for order {}: {}", orderId, ex.toString());
            jdbc.update("UPDATE order_release_retry SET attempts = attempts + 1, updated_at = ? WHERE order_id = ?",
                    Timestamp.from(Instant.now()), orderId);
        }
    }

    // ------------------------------------------------------------------ schedulers

    @Scheduled(fixedDelayString = "${order.compensator.interval-ms:60000}")
    public void compensateStaleIntents() {
        Instant cutoff = Instant.now().minusMillis(STALE_INTENT_MILLIS);
        List<Map<String, Object>> stale = jdbc.queryForList(
                "SELECT id, order_id FROM order_intent WHERE status = ? AND created_at < ? "
                        + "ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED",
                INTENT_OPEN, Timestamp.from(cutoff));
        for (Map<String, Object> row : stale) {
            UUID intentId = (UUID) row.get("id");
            UUID orderId = (UUID) row.get("order_id");
            try {
                // Never release a committed order during cleanup.
                Integer committed = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM takeout_order WHERE id = ?", Integer.class, orderId);
                if (committed != null && committed > 0) {
                    markIntent(intentId, INTENT_COMPLETED);
                    continue;
                }
                deps.release(orderId);
                markIntent(intentId, INTENT_EXPIRED);
            } catch (RuntimeException ex) {
                log.warn("stale intent compensation failed for {}: {}", orderId, ex.toString());
            }
        }
    }

    @Scheduled(fixedDelayString = "${order.release-retry.interval-ms:30000}")
    public void retryReleases() {
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT order_id FROM order_release_retry WHERE completed = FALSE ORDER BY created_at LIMIT 50");
        for (Map<String, Object> row : pending) {
            attemptRelease((UUID) row.get("order_id"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private OrderResponse findExistingOrder(String customerId, String idempotencyKey, String hash) {
        List<Map<String, Object>> rows;
        if (idempotencyKey != null) {
            rows = jdbc.queryForList(
                    "SELECT id, customer_id, status, total_cents, created_at, confirmed_at, cancelled_at, request_hash "
                            + "FROM takeout_order WHERE customer_id = ? AND request_key = ?",
                    customerId, idempotencyKey);
        } else {
            rows = jdbc.queryForList(
                    "SELECT id, customer_id, status, total_cents, created_at, confirmed_at, cancelled_at, request_hash "
                            + "FROM takeout_order WHERE customer_id = ? AND request_hash = ?",
                    customerId, hash);
        }
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        String storedHash = (String) row.get("request_hash");
        if (storedHash != null && !storedHash.equals(hash)) {
            throw OrderProblem.idempotencyConflict();
        }
        return toResponse(row);
    }

    private OrderResponse toResponse(Map<String, Object> row) {
        UUID id = (UUID) row.get("id");
        List<OrderItemResponse> items = jdbc.query(
                "SELECT menu_item_id, item_name, unit_price_cents, quantity FROM order_line WHERE order_id = ? ORDER BY menu_item_id",
                (rs, i) -> new OrderItemResponse(
                        rs.getInt("menu_item_id"),
                        rs.getString("item_name"),
                        rs.getLong("unit_price_cents"),
                        rs.getInt("quantity")),
                id);
        return new OrderResponse(
                id,
                (String) row.get("customer_id"),
                (String) row.get("status"),
                ((Number) row.get("total_cents")).longValue(),
                items,
                toInstant(row.get("created_at")),
                toInstant(row.get("confirmed_at")),
                toInstant(row.get("cancelled_at")));
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }

    private static List<OrderItemRequest> normalize(List<OrderItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw OrderProblem.badRequest("items must not be empty");
        }
        if (items.size() > 20) {
            throw OrderProblem.badRequest("at most 20 items allowed");
        }
        Map<Integer, Integer> merged = new LinkedHashMap<>();
        for (OrderItemRequest item : items) {
            if (item == null || item.menuItemId() <= 0) {
                throw OrderProblem.badRequest("menuItemId must be positive");
            }
            if (item.quantity() < 1 || item.quantity() > 99) {
                throw OrderProblem.badRequest("quantity must be between 1 and 99");
            }
            if (merged.putIfAbsent(item.menuItemId(), item.quantity()) != null) {
                throw OrderProblem.badRequest("duplicate menuItemId");
            }
        }
        List<OrderItemRequest> sorted = new ArrayList<>(merged.entrySet().stream()
                .map(e -> new OrderItemRequest(e.getKey(), e.getValue()))
                .toList());
        sorted.sort(Comparator.comparingInt(OrderItemRequest::menuItemId));
        return sorted;
    }

    private static String canonicalHash(List<OrderItemRequest> items) {
        StringBuilder sb = new StringBuilder();
        for (OrderItemRequest item : items) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(item.menuItemId()).append(':').append(item.quantity());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
