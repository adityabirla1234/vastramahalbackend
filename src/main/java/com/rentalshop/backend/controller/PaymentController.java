package com.rentalshop.backend.controller;

import com.rentalshop.backend.dto.CreatePaymentRequest;
import com.rentalshop.backend.dto.PaymentResponse;
import com.rentalshop.backend.entity.Owner;
import com.rentalshop.backend.security.RequireRole;
import com.rentalshop.backend.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Nested under a booking, not top-level -- a payment only ever makes sense
 * in the context of the booking it's against (same reasoning as ItemImage
 * living under /api/items/{itemId}/images, not its own top-level collection).
 */
@RestController
@RequestMapping("/api/bookings/{bookingId}/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /** Booking Details screen's payment history. Open read, same as the booking lookup itself. */
    @GetMapping
    public ResponseEntity<List<PaymentResponse>> list(@PathVariable Long bookingId) {
        return ResponseEntity.ok(paymentService.listPayments(bookingId));
    }

    /**
     * Admin-only, same as booking status transitions -- recording money
     * received is a staff action. 404 if the booking doesn't exist, 400 if
     * the amount exceeds the outstanding balance (see PaymentService).
     */
    @PostMapping
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<PaymentResponse> create(@PathVariable Long bookingId,
                                                    @Valid @RequestBody CreatePaymentRequest request) {
        return paymentService.recordPayment(bookingId, request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Corrects a mis-entered payment; restores its amount back onto the booking's balance. */
    @DeleteMapping("/{paymentId}")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<Void> delete(@PathVariable Long bookingId, @PathVariable Long paymentId) {
        if (!paymentService.paymentBelongsToBooking(paymentId, bookingId)) {
            return ResponseEntity.notFound().build();
        }
        boolean deleted = paymentService.deletePayment(paymentId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
