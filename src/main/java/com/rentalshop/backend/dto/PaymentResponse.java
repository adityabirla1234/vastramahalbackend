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
                .build();
    }
}
