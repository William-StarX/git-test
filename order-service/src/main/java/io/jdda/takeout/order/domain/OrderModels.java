package io.jdda.takeout.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public order contracts shared with messaging and HTTP layers.
 * Kept in package io.jdda.takeout for compatibility with existing messaging code.
 */
public final class OrderModels {

    private OrderModels() {
    }

    /** RabbitMQ order-placed event contract (unchanged). */
    public record OrderPlaced(UUID eventId, UUID orderId) {
    }

    /** Request item DTO. */
    public record OrderItemRequest(int menuItemId, int quantity) {
    }

    /** Create order request DTO. */
    public record CreateOrderRequest(String customerId, List<OrderItemRequest> items) {
    }

    /** Response item DTO. */
    public record OrderItemResponse(int menuItemId, String name, long unitPriceCents, int quantity) {
    }

    /** Order response DTO. */
    public record OrderResponse(
            UUID id,
            String customerId,
            String status,
            long totalCents,
            List<OrderItemResponse> items,
            Instant createdAt,
            Instant confirmedAt,
            Instant cancelledAt) {
    }

    /** Error response DTO. */
    public record ErrorResponse(String code, String message) {
    }
}
