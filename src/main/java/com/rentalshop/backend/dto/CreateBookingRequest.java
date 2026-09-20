package com.rentalshop.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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

    /**
     * How the advance was paid -- "Cash", "UPI" or "Card". Optional on the
     * wire (older app builds don't send it); the app requires it whenever an
     * advance is entered. Stored as null when blank or when there's no advance
     * (see BookingService.normalizeAdvancePaymentMethod).
     */
    @Size(max = 40)
    private String advancePaymentMethod;

    private String notes;

    /**
     * The accessories staff attached to THIS item row via "Add accessory"
     * on the New Booking form -- pants, dupattas and/or jewellery going out
     * alongside it. Per item, not per bill: in a multi-item batch each
     * CreateBookingRequest carries its own list, and the batch path never
     * merges or copies them between rows.
     *
     * Optional and unconstrained in size: a booking with no accessories
     * sends null or an empty list, which is the common case. Duplicates of
     * the same itemId are collapsed server-side rather than rejected (see
     * BookingService.attachAccessories) -- a double tap on the picker
     * shouldn't fail a booking.
     */
    @Valid
    private List<BookingAccessoryRequest> accessories;

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

    /**
     * Optional "Bill No" typed in on the New Booking form. Trimmed and
     * stored as null if blank (see BookingService.normalizeBillNumber).
     * On a batch call, BookingService.createBookingBatch resolves ONE value
     * for the whole batch and stamps it onto every item, so a bill never
     * ends up with two different numbers even if the client sent them
     * inconsistently.
     */
    @Size(max = 40)
    private String billNumber;
}
