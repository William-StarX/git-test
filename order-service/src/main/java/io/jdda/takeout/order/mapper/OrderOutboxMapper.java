package io.jdda.takeout.order.mapper;

import io.jdda.takeout.order.domain.OrderModels.OrderPlaced;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderOutboxMapper {
    private final JdbcTemplate jdbc;

    public OrderOutboxMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OrderPlaced> lockNextUnpublishedEventSkipLocked() {
        return jdbc.query("""
                SELECT event_id, order_id FROM outbox_event WHERE published_at IS NULL
                ORDER BY created_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new OrderPlaced(rs.getObject("event_id", UUID.class), rs.getObject("order_id", UUID.class)));
    }

    public void markOutboxEventPublished(UUID eventId) {
        jdbc.update("UPDATE outbox_event SET published_at=clock_timestamp() WHERE event_id=?", eventId);
    }

    public Boolean containsMatchingOutboxEvent(UUID eventId, UUID orderId) {
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM outbox_event WHERE event_id=? AND order_id=?)
                """, Boolean.class, eventId, orderId);
    }

    public int insertProcessedEventIfAbsent(UUID eventId, UUID orderId) {
        return jdbc.update("""
                INSERT INTO processed_event (event_id, order_id) VALUES (?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, orderId);
    }

    public void confirmPendingOrder(UUID orderId) {
        jdbc.update("""
                UPDATE takeout_order SET status='CONFIRMED', confirmed_at=clock_timestamp()
                WHERE id=? AND status='PENDING'
                """, orderId);
    }
}
