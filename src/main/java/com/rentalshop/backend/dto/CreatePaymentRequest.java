package com.rentalshop.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class CreatePaymentRequest {

    /**
     * Must be > 0 (not just >= 0 like the amount fields on CreateBookingRequest) --
     * a zero-amount "payment" isn't a real receipt and would silently pollute
     * the booking's payment history for no reason.
     */
    @NotNull
    @DecimalMin(value = "0.01", message = "amount must be greater than zero")
    private BigDecimal amount;

    @NotNull
    private LocalDate paymentDate;

    /** Free-text, e.g. "Cash", "UPI", "Card" -- not an enum, matching schema.sql's plain VARCHAR(40). */
    private String method;

    private String notes;
}
