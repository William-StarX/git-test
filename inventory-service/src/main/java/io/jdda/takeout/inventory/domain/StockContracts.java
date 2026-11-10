package io.jdda.takeout.inventory.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class StockContracts {

    private StockContracts() {
    }

    public record StockView(long menuItemId, int available) {
    }

    public record ReservationItem(
            @NotNull @Min(1) Long menuItemId,
            @NotNull @Min(1) @Max(99) Integer quantity) {
    }

    public record ReservationRequest(
            @NotEmpty @Size(max = 20) List<@Valid @NotNull ReservationItem> items) {
    }

    public record ApiError(String code, String message) {
    }
}
