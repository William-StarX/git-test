package io.jdda.takeout.inventory.application;

import io.jdda.takeout.inventory.domain.StockContracts.ReservationItem;
import io.jdda.takeout.inventory.domain.StockContracts.ReservationRequest;
import io.jdda.takeout.inventory.domain.StockContracts.StockView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {

    private static final String STATUS_RESERVED = "RESERVED";
    private static final String STATUS_RELEASED = "RELEASED";

    private final JdbcTemplate jdbc;

    public ReservationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public StockView getStock(long menuItemId) {
        List<Integer> rows = jdbc.query(
                "SELECT available FROM inventory.stock WHERE menu_item_id = ?",
                (rs, n) -> rs.getInt(1),
                menuItemId);
        if (rows.isEmpty()) {
            throw new InventoryException(HttpStatus.NOT_FOUND, "MENU_ITEM_NOT_FOUND",
                    "Menu item not found: " + menuItemId);
        }
        return new StockView(menuItemId, rows.get(0));
    }

    @Transactional
    public void reserve(UUID reservationId, ReservationRequest request) {
        Map<Long, Integer> normalized = normalize(request.items());
        String hash = hash(normalized);

        jdbc.update(
                "INSERT INTO inventory.reservation (id, status, request_hash) VALUES (?, ?, ?) "
                        + "ON CONFLICT (id) DO NOTHING",
                reservationId, STATUS_RESERVED, hash);

        ReservationRow row = lockReservation(reservationId);

        if (STATUS_RELEASED.equals(row.status())) {
            throw new InventoryException(HttpStatus.CONFLICT, "RESERVATION_RELEASED",
                    "Reservation already released: " + reservationId);
        }

        if (row.requestHash() == null) {
            throw new InventoryException(HttpStatus.CONFLICT, "RESERVATION_CONFLICT",
                    "Reservation cannot be re-reserved: " + reservationId);
        }

        if (!row.requestHash().equals(hash)) {
            throw new InventoryException(HttpStatus.CONFLICT, "RESERVATION_CONFLICT",
                    "Reservation payload mismatch: " + reservationId);
        }

        Integer lineCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory.reservation_line WHERE reservation_id = ?",
                Integer.class, reservationId);
        if (lineCount != null && lineCount > 0) {
            // Already reserved with identical payload: idempotent success, no deduction.
            return;
        }

        for (Map.Entry<Long, Integer> entry : normalized.entrySet()) {
            long menuItemId = entry.getKey();
            int quantity = entry.getValue();
            int updated = jdbc.update(
                    "UPDATE inventory.stock SET available = available - ? "
                            + "WHERE menu_item_id = ? AND available >= ?",
                    quantity, menuItemId, quantity);
            if (updated == 0) {
                Integer exists = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory.stock WHERE menu_item_id = ?",
                        Integer.class, menuItemId);
                if (exists == null || exists == 0) {
                    throw new InventoryException(HttpStatus.NOT_FOUND, "MENU_ITEM_NOT_FOUND",
                            "Menu item not found: " + menuItemId);
                }
                throw new InventoryException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                        "Insufficient stock for menu item: " + menuItemId);
            }
        }

        for (Map.Entry<Long, Integer> entry : normalized.entrySet()) {
            jdbc.update(
                    "INSERT INTO inventory.reservation_line (reservation_id, menu_item_id, quantity) "
                            + "VALUES (?, ?, ?)",
                    reservationId, entry.getKey(), entry.getValue());
        }
    }

    @Transactional
    public void release(UUID reservationId) {
        jdbc.update(
                "INSERT INTO inventory.reservation (id, status, request_hash) VALUES (?, ?, NULL) "
                        + "ON CONFLICT (id) DO NOTHING",
                reservationId, STATUS_RELEASED);

        ReservationRow row = lockReservation(reservationId);

        if (STATUS_RELEASED.equals(row.status())) {
            return;
        }

        List<Map.Entry<Long, Integer>> lines = new ArrayList<>();
        jdbc.query(
                "SELECT menu_item_id, quantity FROM inventory.reservation_line "
                        + "WHERE reservation_id = ? ORDER BY menu_item_id",
                rs -> {
                    lines.add(Map.entry(rs.getLong(1), rs.getInt(2)));
                },
                reservationId);

        for (Map.Entry<Long, Integer> line : lines) {
            jdbc.update(
                    "UPDATE inventory.stock SET available = available + ? WHERE menu_item_id = ?",
                    line.getValue(), line.getKey());
        }

        jdbc.update(
                "UPDATE inventory.reservation SET status = ? WHERE id = ?",
                STATUS_RELEASED, reservationId);
    }

    private ReservationRow lockReservation(UUID reservationId) {
        return jdbc.queryForObject(
                "SELECT status, request_hash FROM inventory.reservation WHERE id = ? FOR UPDATE",
                (rs, n) -> new ReservationRow(rs.getString(1), rs.getString(2)),
                reservationId);
    }

    private static Map<Long, Integer> normalize(List<ReservationItem> items) {
        Map<Long, Integer> normalized = new TreeMap<>();
        for (ReservationItem item : items) {
            normalized.merge(item.menuItemId(), item.quantity(), Integer::sum);
        }
        return normalized;
    }

    private static String hash(Map<Long, Integer> normalized) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Integer> entry : normalized.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record ReservationRow(String status, String requestHash) {
    }

    public static class InventoryException extends RuntimeException {

        private final HttpStatus status;
        private final String code;

        public InventoryException(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public HttpStatus status() {
            return status;
        }

        public String code() {
            return code;
        }
    }
}
