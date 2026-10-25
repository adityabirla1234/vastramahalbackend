package com.rentalshop.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * PATCH /api/bookings/{id} -- edits ONE item row of an existing bill
 * (Booking History "Edit bill" action). Covers everything staff can change
 * about a row after it was booked, short of its dates: swapping it for a
 * different inventory item ("Exchange"), correcting the rental/advance
 * amount, and updating notes/accessories. Every field is optional/nullable
 * and means "leave this alone" when omitted -- the app only ever sends the
 * fields the staff member actually touched in the edit sheet, so a rental
 * amount correction doesn't accidentally overwrite notes with null, etc.
 *
 * [accessories], when present, is always a FULL REPLACEMENT of the row's
 * accessory list (never a merge/diff) -- same "resend everything you want
 * to keep" contract the New Booking form already uses. Sent as null to
 * leave the existing accessories untouched.
 *
 * Same optimistic-lock contract as UpdateBookingStatusRequest: [version]
 * must match the row's current version or the write fails 409 STALE_WRITE.
 */
@Getter
@Setter
public class UpdateBookingItemRequest {

    @NotNull
    private Long version;

    /**
     * "Exchange" -- set to a different inventory item's id to swap this row
     * onto it. Left null for an edit that doesn't touch the item itself
     * (e.g. just fixing the rental amount or notes). Only honoured while
     * the booking is still PENDING/CONFIRMED -- see BookingService for why
     * a picked-up item can no longer be swapped from here.
     */
    private Long itemId;

    @DecimalMin("0.0")
    private BigDecimal rentalAmount;

    @DecimalMin("0.0")
    private BigDecimal advanceAmount;

    /**
     * Replaces the row's notes verbatim, including clearing them with an
     * empty/blank string. Left null (the field simply omitted) to leave
     * the existing notes untouched.
     */
    private String notes;

    /**
     * Sets, replaces or clears this row's fitting work (alteration
     * instructions) -- see Booking.fittingWork. Same three-way contract as
     * [notes]: null (field omitted) leaves whatever is there untouched, a
     * non-blank string replaces it, and a blank string clears it. Only the
     * edit flow can write this; the create/add-item requests deliberately
     * have no such field, since fitting work is something staff add after
     * the bill exists.
     *
     * Capped so a runaway paste can't bloat the row (and every list
     * response that carries it) -- 4000 characters is several paragraphs.
     */
    @Size(max = 4000, message = "Fitting work can be at most 4000 characters")
    private String fittingWork;

    /**
     * Full replacement of this row's accessory list -- see the class doc.
     * Null leaves the existing accessories exactly as they were; an empty
     * list clears them.
     */
    @Valid
    private List<BookingAccessoryRequest> accessories;

    /**
     * Bill No -- a BILL-level field, so a non-null value is applied to every
     * row sharing this row's groupId, not just this one. Null leaves it
     * alone; a blank string clears it.
     */
    @Size(max = 40)
    private String billNumber;

    /**
     * How the advance was paid ("Cash" / "UPI" / "Card"). Null leaves it
     * alone, blank clears it. Always cleared server-side when the advance
     * ends up zero.
     */
    @Size(max = 40)
    private String advancePaymentMethod;

    /**
     * Date edits. Null leaves the date alone. Changing either pickup or
     * return re-runs the same availability check an exchange does, so a
     * date change can never double-book the item (409 BOOKING_CONFLICT).
     * [clearEventDate] = true removes the event date (a LocalDate cannot be
     * "blank"); it wins over [eventDate].
     */
    private LocalDate pickupDate;
    private LocalDate eventDate;
    private LocalDate returnDate;
    private Boolean clearEventDate;

    /**
     * Security deposit correction -- only meaningful once the deposit was
     * actually taken, i.e. while the row is PICKED_UP (409 INVALID_STATE
     * otherwise). Never touches balanceAmount: the deposit is not part of
     * the bill.
     */
    @DecimalMin("0.0")
    private BigDecimal depositAmount;

    /** Null leaves it alone, blank clears it; cleared when the deposit ends up zero. */
    @Size(max = 40)
    private String depositPaymentMethod;
}
