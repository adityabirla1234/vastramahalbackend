package com.rentalshop.backend.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Section 3.7 partial-success rule: one row's outcome within a multi-item
 * batch submission. [success] tells the app which branch to render for
 * this row -- [booking] is populated when true, [errorCode]/[errorMessage]
 * when false. Mirrors the same error shape GlobalExceptionHandler already
 * uses for a single booking (BOOKING_CONFLICT / BAD_REQUEST / INVALID_STATE),
 * so the Android app can reuse its existing per-error-code UI branching.
 */
@Getter
@Builder
public class BookingItemResult {
    private Long itemId;
    private boolean success;
    private BookingResponse booking;
    private String errorCode;
    private String errorMessage;

    public static BookingItemResult ok(BookingResponse booking) {
        return BookingItemResult.builder()
                .itemId(booking.getItemId())
                .success(true)
                .booking(booking)
                .build();
    }

    public static BookingItemResult failed(Long itemId, String errorCode, String errorMessage) {
        return BookingItemResult.builder()
                .itemId(itemId)
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
