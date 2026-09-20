package com.rentalshop.backend.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One payment taken against a whole bill (a group booking), as the app
 * records it: staff enter a single amount for the bill, and the server
 * splits it across the bill's items. This is what comes back.
 *
 * {@link #allocations} is that split -- one {@link PaymentResponse} per item
 * row the money actually landed on, which may be fewer rows than the bill
 * has items (an item already paid off takes none of it). The app doesn't
 * need to render the split to work, but it's returned rather than hidden so
 * "where did my Rs. 5000 go?" is answerable without a second call, and so a
 * per-item Booking Detail showing one of these pieces isn't a surprise.
 *
 * {@link #groupBalanceAfter} is the whole bill's remaining balance once the
 * payment is applied -- the figure the group screen puts in front of staff,
 * saving it from summing the items itself.
 */
@Getter
@Builder
public class GroupPaymentResponse {

    private String groupId;

    /** Shared by every row in {@link #allocations} -- pass it back to DELETE to undo the whole payment. */
    private String groupPaymentRef;

    /** What staff actually entered: the sum of every allocation below. */
    private BigDecimal amount;

    private LocalDate paymentDate;
    private String method;
    private String notes;

    private List<PaymentResponse> allocations;

    private BigDecimal groupBalanceAfter;
}
