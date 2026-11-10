package io.jdda.takeout.inventory.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryMapper {
    private final JdbcTemplate jdbc;

    public InventoryMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Integer> findAvailableStock(long menuItemId) {
        return jdbc.query(
                "SELECT available FROM inventory.stock WHERE menu_item_id = ?",
                (rs, n) -> rs.getInt(1),
                menuItemId);
    }

    public void insertReservationIfAbsent(UUID reservationId, String status, String hash) {
        jdbc.update(
                "INSERT INTO inventory.reservation (id, status, request_hash) VALUES (?, ?, ?) "
                        + "ON CONFLICT (id) DO NOTHING",
                reservationId, status, hash);
    }

    public Integer countReservationLines(UUID reservationId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory.reservation_line WHERE reservation_id = ?",
                Integer.class, reservationId);
    }

    public int deductAvailableStock(long menuItemId, int quantity) {
        return jdbc.update(
                    "UPDATE inventory.stock SET available = available - ? "
                            + "WHERE menu_item_id = ? AND available >= ?",
                    quantity, menuItemId, quantity);
    }

    public Integer countMatchingStockItem(long menuItemId) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory.stock WHERE menu_item_id = ?",
                        Integer.class, menuItemId);
    }

    public void insertReservationLine(UUID reservationId, long menuItemId, int quantity) {
        jdbc.update(
                    "INSERT INTO inventory.reservation_line (reservation_id, menu_item_id, quantity) "
                            + "VALUES (?, ?, ?)",
                    reservationId, menuItemId, quantity);
    }

    public void insertReleaseTombstoneIfAbsent(UUID reservationId, String status) {
        jdbc.update(
                "INSERT INTO inventory.reservation (id, status, request_hash) VALUES (?, ?, NULL) "
                        + "ON CONFLICT (id) DO NOTHING",
                reservationId, status);
    }

    public List<Map.Entry<Long, Integer>> findReservedLinesInStockOrder(UUID reservationId) {
        List<Map.Entry<Long, Integer>> lines = new ArrayList<>();
        jdbc.query(
                "SELECT menu_item_id, quantity FROM inventory.reservation_line "
                        + "WHERE reservation_id = ? ORDER BY menu_item_id",
                rs -> {
                    lines.add(Map.entry(rs.getLong(1), rs.getInt(2)));
                },
                reservationId);
        return lines;
    }

    public void restoreAvailableStock(long menuItemId, int quantity) {
        jdbc.update(
                    "UPDATE inventory.stock SET available = available + ? WHERE menu_item_id = ?",
                    quantity, menuItemId);
    }

    public void markReservationReleased(UUID reservationId, String status) {
        jdbc.update(
                "UPDATE inventory.reservation SET status = ? WHERE id = ?",
                status, reservationId);
    }

    public ReservationRow lockReservationById(UUID reservationId) {
        return jdbc.queryForObject(
                "SELECT status, request_hash FROM inventory.reservation WHERE id = ? FOR UPDATE",
                (rs, n) -> new ReservationRow(rs.getString(1), rs.getString(2)),
                reservationId);
    }

    public record ReservationRow(String status, String requestHash) {
    }
}
