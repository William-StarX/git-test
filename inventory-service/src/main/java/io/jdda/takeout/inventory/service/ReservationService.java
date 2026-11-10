package io.jdda.takeout.inventory.service;

import io.jdda.takeout.inventory.domain.StockContracts.ReservationItem;
import io.jdda.takeout.inventory.domain.StockContracts.ReservationRequest;
import io.jdda.takeout.inventory.domain.StockContracts.StockView;
import io.jdda.takeout.inventory.mapper.InventoryMapper.ReservationRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import io.jdda.takeout.inventory.mapper.InventoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {

    private static final String STATUS_RESERVED = "RESERVED";
    private static final String STATUS_RELEASED = "RELEASED";

    private final InventoryMapper mapper;

    public ReservationService(InventoryMapper mapper) {
        this.mapper = mapper;
    }

    public StockView getStock(long menuItemId) {
        List<Integer> rows = mapper.findAvailableStock(menuItemId);
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

        mapper.insertReservationIfAbsent(reservationId, STATUS_RESERVED, hash);

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

        Integer lineCount = mapper.countReservationLines(reservationId);
        if (lineCount != null && lineCount > 0) {
            // Already reserved with identical payload: idempotent success, no deduction.
            return;
        }

        for (Map.Entry<Long, Integer> entry : normalized.entrySet()) {
            long menuItemId = entry.getKey();
            int quantity = entry.getValue();
            int updated = mapper.deductAvailableStock(menuItemId, quantity);
            if (updated == 0) {
                Integer exists = mapper.countMatchingStockItem(menuItemId);
                if (exists == null || exists == 0) {
                    throw new InventoryException(HttpStatus.NOT_FOUND, "MENU_ITEM_NOT_FOUND",
                            "Menu item not found: " + menuItemId);
                }
                throw new InventoryException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                        "Insufficient stock for menu item: " + menuItemId);
            }
        }

        for (Map.Entry<Long, Integer> entry : normalized.entrySet()) {
            mapper.insertReservationLine(reservationId, entry.getKey(), entry.getValue());
        }
    }

    @Transactional
    public void release(UUID reservationId) {
        mapper.insertReleaseTombstoneIfAbsent(reservationId, STATUS_RELEASED);

        ReservationRow row = lockReservation(reservationId);

        if (STATUS_RELEASED.equals(row.status())) {
            return;
        }

        List<Map.Entry<Long, Integer>> lines = mapper.findReservedLinesInStockOrder(reservationId);

        for (Map.Entry<Long, Integer> line : lines) {
            mapper.restoreAvailableStock(line.getKey(), line.getValue());
        }

        mapper.markReservationReleased(reservationId, STATUS_RELEASED);
    }

    private ReservationRow lockReservation(UUID reservationId) {
        return mapper.lockReservationById(reservationId);
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
