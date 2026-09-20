package com.rentalshop.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Body for POST /api/bookings/mark-picked-up -- the single "Mark picked up"
 * flow on the Booking Detail and Group Booking screens. Everything staff
 * enter in that flow arrives in ONE request so it commits (or fails) as one
 * unit: the items going out, the security deposit and how it was paid, and
 * the part of the bill's outstanding balance collected at the counter and
 * how it was paid. Sending these as separate calls would let a dropped
 * connection leave items marked picked up with no deposit recorded, or a
 * payment taken against a bill whose items never left.
 *
 * All items must belong to the SAME bill (one groupId, or one standalone
 * booking) and every one must currently be CONFIRMED.
 */
@Getter
@Setter
public class MarkPickedUpRequest {

    @NotEmpty
    @Valid
    private List<BillActionItem> items;

    /**
     * The security deposit collected for the items going out, as ONE figure
     * for all of them. Required (send 0 for "none") so the app can never
     * skip the prompt. Stored per row for display only and never folded
     * into rentalAmount/balanceAmount -- see BookingService.updateStatus.
     */
    @NotNull
    @DecimalMin("0.0")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal depositAmount;

    /** "Cash" / "UPI" / "Card". Required when depositAmount is above zero; ignored (stored as null) otherwise. */
    @Size(max = 40)
    private String depositPaymentMethod;

    /**
     * How much of the bill's outstanding balance the customer pays right
     * now. The app pre-fills it with the whole bill balance but staff can
     * lower it; 0 means "collecting nothing at pickup". Recorded as an
     * ordinary payment (PaymentService), so it reduces the balance shown on
     * the bill and appears in its payment history. May not exceed the bill's
     * outstanding balance -- that is a typo, not an overpayment.
     */
    @NotNull
    @DecimalMin("0.0")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal duePaymentAmount;

    /** "Cash" / "UPI" / "Card". Required when duePaymentAmount is above zero. */
    @Size(max = 40)
    private String duePaymentMethod;

    /** The date the app should stamp on the payment -- the device's own local date, as with CreatePaymentRequest. */
    @NotNull
    private LocalDate paymentDate;
}
