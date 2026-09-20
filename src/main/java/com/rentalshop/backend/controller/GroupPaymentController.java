package com.rentalshop.backend.controller;

import com.rentalshop.backend.dto.CreatePaymentRequest;
import com.rentalshop.backend.dto.GroupPaymentResponse;
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
 * Payments for a whole bill. A customer booking four dresses in one visit
 * pays once, for the bill -- so this, not
 * {@link PaymentController}'s per-booking path, is where money is collected
 * for a multi-item booking. PaymentService.recordPayment refuses a payment
 * aimed at one item of such a bill, so the two controllers can't disagree.
 *
 * Mapped alongside GET /api/bookings/group/{groupId} on BookingController,
 * the endpoint that lists the bill's items. Both take the group UUID shared
 * by the bill's rows; there is no group entity behind either (see
 * Booking.groupId). The literal "group" segment is matched ahead of
 * PaymentController's {bookingId} template, so the two never collide.
 */
@RestController
@RequestMapping("/api/bookings/group/{groupId}/payments")
@RequiredArgsConstructor
public class GroupPaymentController {

    private final PaymentService paymentService;

    /**
     * The bill's payment history: every payment against any of its items,
     * oldest first. Open read, same as the group booking lookup itself.
     *
     * Returns the individual rows rather than pre-collapsing them by
     * groupPaymentRef -- the app groups them for display (one entered
     * payment = one line), and leaving that to the client keeps any
     * payments made against the bill's items before it grew a second item
     * visible here too, rather than silently dropped for having no ref.
     */
    @GetMapping
    public ResponseEntity<List<PaymentResponse>> list(@PathVariable String groupId) {
        return ResponseEntity.ok(paymentService.listGroupPayments(groupId));
    }

    /**
     * Records one payment for the bill; the service splits it across the
     * bill's items (see PaymentService.recordGroupPayment). Admin-only, same
     * as the per-booking path. 404 if no booking carries this groupId, 400
     * if the amount exceeds the bill's total outstanding balance.
     */
    @PostMapping
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<GroupPaymentResponse> create(@PathVariable String groupId,
                                                       @Valid @RequestBody CreatePaymentRequest request) {
        return paymentService.recordGroupPayment(groupId, request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Reverses a bill-level payment in full, by the ref the POST returned --
     * every slice it was split into, put back on the booking it came off.
     * Keyed on the ref rather than a payment id precisely because one entered
     * payment can be several rows: deleting them one id at a time would let
     * staff stop halfway.
     */
    @DeleteMapping("/{groupPaymentRef}")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<Void> delete(@PathVariable String groupId,
                                       @PathVariable String groupPaymentRef) {
        boolean deleted = paymentService.deleteGroupPayment(groupId, groupPaymentRef);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
