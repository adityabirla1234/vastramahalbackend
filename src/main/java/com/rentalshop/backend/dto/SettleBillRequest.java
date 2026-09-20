package com.rentalshop.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Body for PATCH /api/bookings/{id}/settle -- the Amount Due Bills action
 * (Customers section) that closes out a bill still marked DUE after its
 * final return. Distinct from UpdateBookingStatusRequest's settlementStatus
 * field: that one is only usable in the same call that transitions a
 * booking to RETURNED. This endpoint is for settling later, once the bill
 * already sits in RETURNED/DUE and staff collects the outstanding balance
 * afterwards -- see BookingService.settleBill.
 */
@Getter
@Setter
public class SettleBillRequest {

    /** Same optimistic-lock contract as UpdateBookingStatusRequest.version. */
    @NotNull
    private Long version;
}
