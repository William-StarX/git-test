package io.jdda.takeout.order.domain;

import org.springframework.http.HttpStatus;

/**
 * Domain problem carrying an HTTP status and a stable error code.
 */
public class OrderProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public OrderProblem(HttpStatus status, String code, String message) {
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

    public static OrderProblem badRequest(String message) {
        return new OrderProblem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static OrderProblem idempotencyConflict() {
        return new OrderProblem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                "idempotency key reused with different items");
    }

    public static OrderProblem insufficientStock() {
        return new OrderProblem(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "insufficient stock");
    }

    public static OrderProblem orderNotFound() {
        return new OrderProblem(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "order not found");
    }

    public static OrderProblem menuItemNotFound() {
        return new OrderProblem(HttpStatus.NOT_FOUND, "MENU_ITEM_NOT_FOUND", "menu item not found");
    }

    public static OrderProblem serviceUnavailable(String message) {
        return new OrderProblem(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", message);
    }
}
