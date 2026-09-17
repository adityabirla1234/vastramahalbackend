package com.rentalshop.backend.exception;

/** Missing, unknown, or deactivated X-Device-Token. Mapped to HTTP 401. */
public class UnauthenticatedException extends RuntimeException {
    public UnauthenticatedException(String message) {
        super(message);
    }
}
