package com.rentalshop.backend.controller;

import com.rentalshop.backend.dto.BookingResponse;
import com.rentalshop.backend.dto.CreateBookingRequest;
import com.rentalshop.backend.dto.UpdateBookingStatusRequest;
import com.rentalshop.backend.entity.Booking;
import com.rentalshop.backend.entity.Owner;
import com.rentalshop.backend.repository.BookingRepository;
import com.rentalshop.backend.security.CurrentDevice;
import com.rentalshop.backend.security.RequireRole;
import com.rentalshop.backend.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final BookingRepository bookingRepository;

    /**
     * All Bookings / Returns / Item Calendar / Customer History screens all
     * need the same shape of filtered list -- see BookingRepository.search's
     * doc for filter semantics. Every param is optional; e.g.
     * ?status=PICKED_UP&dueOnOrBefore=2026-12-15 is exactly a "Returns due"
     * view. No @RequireRole -- reads are open to both roles, same as the
     * single-booking lookup below.
     */
    @GetMapping
    public ResponseEntity<List<BookingResponse>> list(
            @RequestParam(required = false) Booking.BookingStatus status,
            @RequestParam(required = false) Long itemId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueOnOrBefore) {
        return ResponseEntity.ok(bookingService.listBookings(status, itemId, customerId, dueOnOrBefore));
    }

    /**
     * Needed by the app before it can call PATCH .../status: the client must
     * know the booking's current `version` to send back an optimistic-lock
     * value that isn't already stale. No @RequireRole -- both Admin and
     * Viewer devices can read a booking, same as item/customer reads.
     */
    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> findById(@PathVariable Long id) {
        return bookingService.getBooking(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Admin-only, now actually enforced server-side by DeviceAuthInterceptor
     * reading this method's @RequireRole(ADMIN) — a Viewer's token gets 403
     * before this method body ever runs (Section 12).
     *
     * request.createdBy is intentionally overwritten with the authenticated
     * device's own id below rather than trusted from the request body: a
     * client-supplied createdBy would let any device attribute a booking to
     * someone else.
     *
     * Returns 201 with the confirmed booking, or 409 (BOOKING_CONFLICT) if the
     * dates overlap an existing booking. The Android app must treat anything
     * other than a 2xx response as "not confirmed" (Section 5).
     */
    @PostMapping
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest request) {
        request.setCreatedBy(CurrentDevice.get().ownerId());
        BookingResponse response = bookingService.createBooking(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Booking lifecycle transitions (Picked Up / Returned / Cancelled).
     * Admin-only, same as create -- a status change is a staff action, not
     * something a read-only Viewer device should be able to trigger.
     *
     * 200 with the updated booking, 404 if the id doesn't exist, 409
     * INVALID_STATE for an out-of-order transition (e.g. PENDING straight to
     * RETURNED), or 409 STALE_WRITE if `version` doesn't match -- identical
     * conflict semantics to the Item edit endpoint, so the Android app can
     * reuse the same "refresh and retry" handling for both.
     */
    @PatchMapping("/{id}/status")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<BookingResponse> updateStatus(
            @PathVariable Long id, @Valid @RequestBody UpdateBookingStatusRequest request) {
        return bookingService.updateStatus(id, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
