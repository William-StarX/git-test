package io.jdda.takeout;

import org.springframework.http.HttpStatus;

final class OrderFailure extends RuntimeException {
    final HttpStatus status;
    final String code;

    OrderFailure(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
