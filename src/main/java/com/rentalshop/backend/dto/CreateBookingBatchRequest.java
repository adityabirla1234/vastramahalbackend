package com.rentalshop.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Section 3.7 multi-item single-form booking: one New Booking submission
 * with several item rows, each carrying its own pickup/return dates,
 * rental/advance amounts and idempotencyKey (per-row, so a retry of just
 * one failed row is safe/idempotent independently of the others).
 *
 * Every item shares the same customerId in practice (one customer, one
 * counter visit), but that's a client-side convention, not enforced here --
 * each CreateBookingRequest is otherwise self-sufficient.
 */
@Getter
@Setter
public class CreateBookingBatchRequest {

    @NotEmpty
    @Valid
    private List<CreateBookingRequest> items;
}
