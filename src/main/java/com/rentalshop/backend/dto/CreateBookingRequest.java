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

    @DecimalMin("0.0")
    private BigDecimal depositAmount = BigDecimal.ZERO;

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
}
