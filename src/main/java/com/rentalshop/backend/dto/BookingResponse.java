package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.Booking;
import lombok.Getter;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public class BookingResponse {
    private Long id;
    private String bookingNumber;
    private Long itemId;
    private String itemCode;
    private String itemName;
    private Long customerId;
    private String customerName;
    private LocalDate pickupDate;
    private LocalDate eventDate;
    private LocalDate returnDate;
    private BigDecimal rentalAmount;
    private BigDecimal depositAmount;
    private BigDecimal advanceAmount;
    private BigDecimal balanceAmount;
    private Booking.BookingStatus status;
    /** Optimistic-lock value for PATCH /api/bookings/{id}/status -- see UpdateBookingStatusRequest. */
    private Long version;
    /** Section 3.7 multi-item booking -- null for a standalone booking.
     * See Booking.groupId's Javadoc; GET /api/bookings/group/{groupId}
     * fetches every row sharing this value for the "overall bill" view. */
    private String groupId;

    public static BookingResponse from(Booking b) {
        return BookingResponse.builder()
                .id(b.getId())
                .bookingNumber(b.getBookingNumber())
                .itemId(b.getItem().getId())
                .itemCode(b.getItem().getItemCode())
                .itemName(b.getItem().getName())
                .customerId(b.getCustomer().getId())
                .customerName(b.getCustomer().getName())
                .pickupDate(b.getPickupDate())
                .eventDate(b.getEventDate())
                .returnDate(b.getReturnDate())
                .rentalAmount(b.getRentalAmount())
                .depositAmount(b.getDepositAmount())
                .advanceAmount(b.getAdvanceAmount())
                .balanceAmount(b.getBalanceAmount())
                .status(b.getStatus())
                .version(b.getVersion())
                .groupId(b.getGroupId())
                .build();
    }
}
