package io.jdda.takeout;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class OrderModels {
    private OrderModels() {}

    record RequestedItem(@Positive long menuItemId, @Min(1) @Max(99) int quantity) {}

    record PlaceOrder(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String customerId,
                      @NotEmpty @Size(max = 20) List<@NotNull @Valid RequestedItem> items) {}

    record MenuItem(long id, String name, long priceCents) {}
    record Stock(long menuItemId, int available) {}
    record OrderItem(long menuItemId, String name, long unitPriceCents, int quantity) {}

    record OrderView(UUID id, String customerId, String status, long totalCents, List<OrderItem> items,
                     Instant createdAt, Instant confirmedAt, Instant cancelledAt) {}

    record OrderResult(OrderView order, boolean created) {}
    record OrderPlaced(UUID eventId, UUID orderId) {}
}
