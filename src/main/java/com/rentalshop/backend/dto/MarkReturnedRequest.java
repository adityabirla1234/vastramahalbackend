package com.rentalshop.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Body for POST /api/bookings/mark-returned -- the single "Mark returned"
 * flow. Carries the items that came back plus staff's answers to the two
 * closing questions, all in one request for the same all-or-nothing reason
 * as {@link MarkPickedUpRequest}.
 *
 * All items must belong to the SAME bill and every one must currently be
 * PICKED_UP.
 */
@Getter
@Setter
public class MarkReturnedRequest {

    @NotEmpty
    @Valid
    private List<BillActionItem> items;

    /**
     * "Did you give the security deposit back to the customer?" -- true for
     * Yes, false for No. Required whenever the bill still holds a deposit
     * that hasn't been handed back; null (and ignored) when it has none, in
     * which case the app doesn't ask the question at all.
     */
    private Boolean depositReturned;

    /** Required (non-blank) when depositReturned is false; ignored otherwise. */
    @Size(max = 500)
    private String depositNotReturnedReason;

    /**
     * "Is the bill settled full and final, with no payment due remaining?"
     * -- true for Yes, false for No. Yes clears whatever balance is left on
     * the bill to zero; No leaves it exactly as it stands so it shows up
     * under Amount Due Bills.
     */
    @NotNull
    private Boolean billSettled;
}
