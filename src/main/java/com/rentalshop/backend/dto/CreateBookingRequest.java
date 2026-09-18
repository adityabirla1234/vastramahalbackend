package com.rentalshop.backend.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class CreateBookingRequest {

    @NotNull
    private Long itemId;

    @NotNull
    private Long customerId;

    @NotNull
    private LocalDate pickupDate;

    private LocalDate eventDate;

    @NotNull
    private LocalDate returnDate;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal rentalAmount;

    // No depositAmount field by design -- a booking is created with just
    // rental + advance. Booking.depositAmount defaults to ZERO in the entity
    // and is only ever set later, at pickup time, via
    // PATCH /bookings/{id}/status (UpdateBookingStatusRequest.depositAmount).
    @DecimalMin("0.0")
    private BigDecimal advanceAmount = BigDecimal.ZERO;

    private String notes;

    /**
     * Generated ONCE on the Android device when the user taps "Confirm Booking"
     * (e.g. UUID.randomUUID().toString()), and reused verbatim on every retry of
     * the same tap — including retries after a timeout where the app doesn't
     * know if the first attempt succeeded. This is what makes booking creation
     * safe to retry blindly on flaky connections.
     */
    @NotBlank
    private String idempotencyKey;

    /**
     * Device/owner id performing the booking. NOT read from the client —
     * BookingController overwrites this with the authenticated device's own
     * id (CurrentDevice.get().ownerId()) before calling the service, so
     * there's no @NotNull here and the Android app doesn't need to send it.
     */
    private Long createdBy;

    /**
     * Section 3.7 multi-item booking: when this item is submitted as part
     * of a batch via POST /api/bookings/batch, BookingService.createBookingBatch
     * overwrites this with the shared group UUID before creating each item --
     * a client-supplied value is never trusted directly on a batch call.
     * Left null for a standalone booking made via this plain
     * POST /api/bookings path (no group).
     */
    private String groupId;
}
