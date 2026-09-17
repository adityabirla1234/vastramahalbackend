package com.rentalshop.backend.exception;

/** Authenticated device is a VIEWER hitting an @RequireRole(ADMIN) endpoint. Mapped to HTTP 403. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
