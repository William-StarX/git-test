package io.jdda.takeout.inventory.controller;

import io.jdda.takeout.inventory.service.ReservationService;
import io.jdda.takeout.inventory.domain.StockContracts.ApiError;
import io.jdda.takeout.inventory.domain.StockContracts.ReservationRequest;
import io.jdda.takeout.inventory.domain.StockContracts.StockView;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InventoryController {

    private final ReservationService reservationService;

    public InventoryController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @GetMapping("/api/menu/{id}/stock")
    public StockView stock(@PathVariable("id") long id) {
        return reservationService.getStock(id);
    }

    @PostMapping("/internal/reservations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reserve(@PathVariable("id") UUID id, @Valid @RequestBody ReservationRequest request) {
        reservationService.reserve(id, request);
    }

    @DeleteMapping("/internal/reservations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable("id") UUID id) {
        reservationService.release(id);
    }

    @ExceptionHandler(ReservationService.InventoryException.class)
    public ResponseEntity<ApiError> handleInventory(ReservationService.InventoryException ex) {
        return ResponseEntity.status(ex.status()).body(new ApiError(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Malformed request"));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Malformed request"));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Malformed request"));
    }
}
