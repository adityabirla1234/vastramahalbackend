package com.rentalshop.backend.controller;

import com.rentalshop.backend.dto.AddBookingItemRequest;
import com.rentalshop.backend.dto.BookingBatchResponse;
import com.rentalshop.backend.dto.BookingResponse;
import com.rentalshop.backend.dto.CreateBookingBatchRequest;
import com.rentalshop.backend.dto.CreateBookingRequest;
import com.rentalshop.backend.dto.MarkPickedUpRequest;
import com.rentalshop.backend.dto.MarkReturnedRequest;
import com.rentalshop.backend.dto.SettleBillRequest;
import com.rentalshop.backend.dto.UpdateBookingItemRequest;
import com.rentalshop.backend.dto.UpdateBookingStatusRequest;
import com.rentalshop.backend.entity.Booking;
import com.rentalshop.backend.entity.Owner;
import com.rentalshop.backend.repository.BookingRepository;
import com.rentalshop.backend.security.CurrentDevice;
import com.rentalshop.backend.security.RequireRole;
import com.rentalshop.backend.service.BillActionService;
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
    private final BillActionService billActionService;

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
     * Section 3.7 multi-item single-form booking: one New Booking submission
     * with several item rows. Admin-only, same posture as the single-item
     * create above -- createdBy is overwritten per-item here for the same
     * reason (never trust a client-supplied createdBy).
     *
     * Returns 201 only if every item succeeded; otherwise 207 Multi-Status,
     * with the per-item breakdown in the body (BookingBatchResponse.results)
     * so the app can show exactly which rows need fixing and resubmitting --
     * one item's conflict never blocks or rolls back the others (Section
     * 3.7 / 14.9 partial-success rule).
     */
    @PostMapping("/batch")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<BookingBatchResponse> createBatch(@Valid @RequestBody CreateBookingBatchRequest request) {
        Long ownerId = CurrentDevice.get().ownerId();
        request.getItems().forEach(item -> item.setCreatedBy(ownerId));

        BookingBatchResponse response = bookingService.createBookingBatch(request);
        HttpStatus status = response.allSucceeded() ? HttpStatus.CREATED : HttpStatus.MULTI_STATUS;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Every booking sharing one groupId, for the "overall bill" view on a
     * multi-item booking session -- the app sums these client-side rather
     * than reading a stored total, since there's no separate booking-group
     * entity. No @RequireRole -- a read, same posture as the other lookups.
     */
    @GetMapping("/group/{groupId}")
    public ResponseEntity<List<BookingResponse>> findByGroup(@PathVariable String groupId) {
        return ResponseEntity.ok(bookingService.getGroupBookings(groupId));
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

    /**
     * The single "Mark picked up" flow (Booking Detail and Group Booking
     * screens): marks the selected CONFIRMED items PICKED_UP, records the
     * security deposit and how it was paid, and takes whatever part of the
     * bill's balance was collected at the counter -- all in one transaction,
     * see BillActionService.markPickedUp. Admin-only, same posture as every
     * other booking write.
     *
     * 200 with EVERY row of the affected bill as it stands afterwards
     * (versions included, so the app can act again straight away), 400 for a
     * missing payment method or an amount above the bill's balance, 409
     * INVALID_STATE if an item isn't CONFIRMED, or 409 STALE_WRITE if any
     * item's `version` is out of date -- in every failure case nothing was
     * changed.
     */
    @PostMapping("/mark-picked-up")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<List<BookingResponse>> markPickedUp(@Valid @RequestBody MarkPickedUpRequest request) {
        return ResponseEntity.ok(billActionService.markPickedUp(request));
    }

    /**
     * The single "Mark returned" flow: marks the selected PICKED_UP items
     * RETURNED and records whether the security deposit was given back (with
     * a written reason when it wasn't) and whether the bill is settled full
     * and final -- see BillActionService.markReturned. Admin-only.
     *
     * Same response and failure contract as mark-picked-up above; 400 also
     * covers a "No" to the deposit question with no reason.
     */
    @PostMapping("/mark-returned")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<List<BookingResponse>> markReturned(@Valid @RequestBody MarkReturnedRequest request) {
        return ResponseEntity.ok(billActionService.markReturned(request));
    }

    /**
     * Amount Due Bills (Customers section): closes out a bill left DUE at
     * final return. Admin-only, same posture as the status-transition
     * endpoint above -- collecting/confirming a payment in full is a staff
     * action, not something a read-only Viewer device should trigger.
     *
     * [id] can be any booking belonging to the bill; for a group booking
     * every RETURNED/DUE sibling sharing its groupId is settled in the same
     * call (see BookingService.settleBill). 200 with the updated booking,
     * 404 if the id doesn't exist, 409 INVALID_STATE if the bill isn't
     * currently sitting in RETURNED/DUE (already settled, or not returned
     * yet), or 409 STALE_WRITE if `version` doesn't match -- same conflict
     * semantics as PATCH .../status, so the app can reuse its existing
     * "refresh and retry" handling here too.
     *
     * There's no separate GET for the Amount Due Bills list itself -- the
     * app builds it from GET /api/bookings?status=RETURNED, filtering to
     * settlementStatus=DUE and grouping by groupId client-side, same "no
     * stored bill total" philosophy as the group-bill view above.
     */
    @PatchMapping("/{id}/settle")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<BookingResponse> settleBill(
            @PathVariable Long id, @Valid @RequestBody SettleBillRequest request) {
        return bookingService.settleBill(id, request.getVersion())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Booking History "Edit bill" action, on one item row: exchange it for a
     * different inventory item, correct its rental/advance amount, or
     * replace its notes/accessories -- see UpdateBookingItemRequest's
     * Javadoc for exactly what each field does. Admin-only, same posture as
     * every other booking write.
     *
     * 200 with the updated row (its balanceAmount already re-derived --
     * the app never has to compute that itself), 404 if the id doesn't
     * exist, 409 BOOKING_CONFLICT if an item exchange's target item isn't
     * free for this row's dates, 409 INVALID_STATE if the row is closed
     * out (or, for an exchange specifically, already picked up), or 409
     * STALE_WRITE if `version` doesn't match -- identical conflict
     * semantics to PATCH .../status, so the app can reuse the same
     * "refresh and retry" handling.
     */
    @PatchMapping("/{id}")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<BookingResponse> updateItem(
            @PathVariable Long id, @Valid @RequestBody UpdateBookingItemRequest request) {
        return bookingService.updateBookingItem(id, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Booking History "Remove item" action: takes this one row off its
     * bill (see BookingService.removeBookingItem for what that actually
     * does -- never a hard delete). `version` travels as a query param
     * since a DELETE carries no conventional body; the app reads it off
     * the row exactly like it already does for PATCH .../status.
     *
     * 200 with the now-cancelled row (so the app can show it struck
     * through in the bill's item list rather than having to refetch),
     * 404 if the id doesn't exist, 409 INVALID_STATE if the row can't be
     * removed in its current status (already closed out, or already
     * picked up), or 409 STALE_WRITE if `version` doesn't match.
     * Admin-only, same posture as every other booking write.
     */
    @DeleteMapping("/{id}")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<BookingResponse> removeItem(
            @PathVariable Long id, @RequestParam Long version) {
        return bookingService.removeBookingItem(id, version)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Booking History "Add item to bill" action: adds a brand-new row to
     * whatever bill [id] belongs to -- see BookingService.addItemToBill
     * for how a standalone booking ("booking per row") becomes a group the
     * first time this is called on it. request.createdBy is overwritten
     * with the authenticated device's own id, same reasoning as create()
     * above. Admin-only, same posture as every other booking write.
     *
     * Returns 201 with the new row, 404 if [id] doesn't exist, or 409
     * BOOKING_CONFLICT / INVALID_STATE with the same meaning as the plain
     * create endpoint -- the app should handle this response exactly like
     * a failed POST /api/bookings.
     */
    @PostMapping("/{id}/items")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<BookingResponse> addItem(
            @PathVariable Long id, @Valid @RequestBody AddBookingItemRequest request) {
        Long ownerId = CurrentDevice.get().ownerId();
        return bookingService.addItemToBill(id, request, ownerId)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
