package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.Payment;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class PaymentResponse {
    private Long id;
    private Long bookingId;
    private BigDecimal amount;
    private LocalDate paymentDate;
    private String method;
    private String notes;
    private LocalDateTime createdAt;

    /**
     * The booking's remaining balance AFTER this payment was applied --
     * saves the Booking Details screen a second round-trip to show
     * "Rs. X remaining" right next to the payment that just got recorded.
     */
    private BigDecimal bookingBalanceAfter;

    /**
     * Set when this row is one slice of a bill-level payment -- see
     * Payment.groupPaymentRef. The app uses it to collapse the slices back
     * into the single payment staff entered, and to show on a per-item
     * Booking Detail that a payment came from the bill rather than from
     * that item. Null for an ordinary single-booking payment.
     */
    private String groupPaymentRef;

    public static PaymentResponse from(Payment p) {
        return PaymentResponse.builder()
                .id(p.getId())
                .bookingId(p.getBooking().getId())
                .amount(p.getAmount())
                .paymentDate(p.getPaymentDate())
                .method(p.getMethod())
                .notes(p.getNotes())
                .createdAt(p.getCreatedAt())
                .bookingBalanceAfter(p.getBooking().getBalanceAmount())
                .groupPaymentRef(p.getGroupPaymentRef())
                .build();
    }
}
