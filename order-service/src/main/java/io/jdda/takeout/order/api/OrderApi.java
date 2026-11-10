package io.jdda.takeout.order.api;

import io.jdda.takeout.OrderModels.CreateOrderRequest;
import io.jdda.takeout.OrderModels.ErrorResponse;
import io.jdda.takeout.OrderModels.OrderResponse;
import io.jdda.takeout.order.application.OrderService;
import io.jdda.takeout.order.domain.OrderProblem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderApi {

    private static final String IDEMPOTENCY_KEY_PATTERN = "[A-Za-z0-9._-]{1,128}";

    private final OrderService orderService;

    public OrderApi(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN, message = "invalid Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        OrderService.CreateResult result = orderService.create(idempotencyKey, request);
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/orders/" + result.order().id()))
                    .body(result.order());
        }
        return ResponseEntity.ok(result.order());
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable("id") UUID id) {
        return orderService.get(id);
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable("id") UUID id) {
        return orderService.cancel(id);
    }

    @ExceptionHandler(OrderProblem.class)
    public ResponseEntity<ErrorResponse> handleProblem(OrderProblem problem) {
        return ResponseEntity.status(problem.status())
                .body(new ErrorResponse(problem.code(), problem.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            org.springframework.web.bind.MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.badRequest().body(new ErrorResponse("BAD_REQUEST", message));
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(
            org.springframework.web.bind.MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", "missing header " + ex.getHeaderName()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse("BAD_REQUEST", "malformed request body"));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse("BAD_REQUEST", "invalid path parameter"));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        return ResponseEntity.status(404).body(new ErrorResponse("ORDER_NOT_FOUND", "not found"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        return ResponseEntity.status(500).body(new ErrorResponse("INTERNAL_ERROR", "unexpected error"));
    }

    // Unused imports guard for validation annotations used on nested DTOs.
    @SuppressWarnings("unused")
    private static void validationMarkers(@NotNull Object o, @NotBlank String s, @Size(max = 20) java.util.List<?> l) {
    }
}
