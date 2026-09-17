package com.rentalshop.backend.exception;

/**
 * Thrown when the requested date range overlaps an existing active booking.
 * Controller layer maps this to HTTP 409 Conflict. The Android app must show
 * the booking as NOT confirmed on this response — never optimistically mark
 * it confirmed and reconcile later (Section 5, offline-first rules).
 */
public class BookingConflictException extends RuntimeException {
    public BookingConflictException(String message) {
        super(message);
    }
}
