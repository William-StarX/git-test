package io.jdda.takeout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static io.jdda.takeout.OrderModels.*;

@Service
class OrderService {
    private record Existing(UUID id, String requestHash) {}
    private record OrderHeader(UUID id, String customerId, String status, long totalCents,
                               Instant createdAt, Instant confirmedAt, Instant cancelledAt) {}

    private final JdbcTemplate jdbc;

    OrderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public OrderResult place(String key, PlaceOrder request) {
        if (!key.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new OrderFailure(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Provide an Idempotency-Key of 1 to 128 characters.");
        }
        var quantities = new TreeMap<Long, Integer>();
        for (RequestedItem item : request.items()) {
            if (quantities.putIfAbsent(item.menuItemId(), item.quantity()) != null) {
                throw new OrderFailure(HttpStatus.BAD_REQUEST, "DUPLICATE_MENU_ITEM", "Each menu item may occur once per order.");
            }
        }
        String hash = fingerprint(quantities);
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO takeout_order (id, customer_id, request_key, request_hash, status, total_cents)
                VALUES (?, ?, ?, ?, 'PENDING', 0)
                ON CONFLICT (customer_id, request_key) DO NOTHING
                """, id, request.customerId(), key, hash);
        if (inserted == 0) {
            Existing existing = jdbc.queryForObject("""
                    SELECT id, request_hash FROM takeout_order WHERE customer_id=? AND request_key=?
                    """, (rs, row) -> new Existing(rs.getObject("id", UUID.class), rs.getString("request_hash")),
                    request.customerId(), key);
            if (!hash.equals(existing.requestHash())) {
                throw new OrderFailure(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "This key was used with a different order.");
            }
            return new OrderResult(view(load(existing.id(), false)), false);
        }

        long total = 0;
        // Every order locks inventory in item ID order, including multi-item orders.
        for (var line : quantities.entrySet()) {
            long itemId = line.getKey();
            int quantity = line.getValue();
            MenuItem item = reserve(itemId, quantity);
            total += item.priceCents() * quantity;
            jdbc.update("""
                    INSERT INTO order_line (order_id, menu_item_id, item_name, unit_price_cents, quantity)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, itemId, item.name(), item.priceCents(), quantity);
        }
        jdbc.update("UPDATE takeout_order SET total_cents=? WHERE id=?", total, id);
        jdbc.update("INSERT INTO outbox_event (event_id, order_id) VALUES (?, ?)", UUID.randomUUID(), id);
        return new OrderResult(view(load(id, false)), true);
    }

    private MenuItem reserve(long itemId, int quantity) {
        var items = jdbc.query("""
                UPDATE menu_item SET stock=stock-? WHERE id=? AND stock>=?
                RETURNING id, name, price_cents
                """, (rs, row) -> new MenuItem(rs.getLong("id"), rs.getString("name"), rs.getLong("price_cents")),
                quantity, itemId, quantity);
        if (!items.isEmpty()) return items.getFirst();
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM menu_item WHERE id=?)", Boolean.class, itemId));
        if (!exists) throw new OrderFailure(HttpStatus.NOT_FOUND, "MENU_ITEM_NOT_FOUND", "Menu item " + itemId + " does not exist.");
        throw new OrderFailure(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "Insufficient stock for menu item " + itemId + ".");
    }

    @Transactional(readOnly = true)
    public OrderView get(UUID id) {
        return view(load(id, false));
    }

    @Transactional
    public OrderView cancel(UUID id) {
        OrderHeader order = load(id, true);
        OrderView current = view(order);
        if ("CANCELLED".equals(order.status())) return current;
        for (OrderItem item : current.items()) {
            jdbc.update("UPDATE menu_item SET stock=stock+? WHERE id=?", item.quantity(), item.menuItemId());
        }
        jdbc.update("UPDATE takeout_order SET status='CANCELLED', cancelled_at=clock_timestamp() WHERE id=?", id);
        return view(load(id, false));
    }

    private OrderHeader load(UUID id, boolean lock) {
        String sql = "SELECT * FROM takeout_order WHERE id=?" + (lock ? " FOR UPDATE" : "");
        var rows = jdbc.query(sql, (rs, row) -> {
            OffsetDateTime confirmed = rs.getObject("confirmed_at", OffsetDateTime.class);
            OffsetDateTime cancelled = rs.getObject("cancelled_at", OffsetDateTime.class);
            return new OrderHeader(rs.getObject("id", UUID.class), rs.getString("customer_id"), rs.getString("status"),
                    rs.getLong("total_cents"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    confirmed == null ? null : confirmed.toInstant(), cancelled == null ? null : cancelled.toInstant());
        }, id);
        if (rows.isEmpty()) throw new OrderFailure(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order does not exist.");
        return rows.getFirst();
    }

    private OrderView view(OrderHeader order) {
        List<OrderItem> items = jdbc.query("""
                SELECT menu_item_id, item_name, unit_price_cents, quantity
                FROM order_line WHERE order_id=? ORDER BY menu_item_id
                """, (rs, row) -> new OrderItem(rs.getLong("menu_item_id"), rs.getString("item_name"),
                rs.getLong("unit_price_cents"), rs.getInt("quantity")), order.id());
        return new OrderView(order.id(), order.customerId(), order.status(), order.totalCents(),
                items, order.createdAt(), order.confirmedAt(), order.cancelledAt());
    }

    private static String fingerprint(Map<Long, Integer> quantities) {
        var canonical = new StringJoiner(",");
        quantities.forEach((item, quantity) -> canonical.add(item + ":" + quantity));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }
}
