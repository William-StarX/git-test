package io.jdda.takeout;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static io.jdda.takeout.OrderModels.*;

@RestController
@RequestMapping("/api")
class TakeoutApi {
    private final MenuService menu;
    private final OrderService orders;

    TakeoutApi(MenuService menu, OrderService orders) {
        this.menu = menu;
        this.orders = orders;
    }

    @GetMapping("/menu")
    ResponseEntity<List<MenuItem>> menu() {
        MenuService.Catalog catalog = menu.catalog();
        return ResponseEntity.ok().header("X-Cache", catalog.cache()).body(catalog.items());
    }

    @GetMapping("/menu/{id}/stock")
    Stock stock(@PathVariable long id) {
        return menu.stock(id);
    }

    @PostMapping("/orders")
    ResponseEntity<OrderView> place(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody PlaceOrder request) {
        OrderResult result = orders.place(key, request);
        if (!result.created()) return ResponseEntity.ok(result.order());
        return ResponseEntity.created(URI.create("/api/orders/" + result.order().id())).body(result.order());
    }

    @GetMapping("/orders/{id}")
    OrderView get(@PathVariable UUID id) {
        return orders.get(id);
    }

    @PostMapping("/orders/{id}/cancel")
    OrderView cancel(@PathVariable UUID id) {
        return orders.cancel(id);
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(OrderFailure.class)
    ResponseEntity<Map<String, String>> domain(OrderFailure error) {
        return ResponseEntity.status(error.status).body(Map.of("code", error.code, "message", error.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "Check the request fields and headers."));
    }
}
