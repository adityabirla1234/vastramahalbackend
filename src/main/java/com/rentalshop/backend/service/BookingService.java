package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.BookingResponse;
import com.rentalshop.backend.dto.CreateBookingRequest;
import com.rentalshop.backend.dto.UpdateBookingStatusRequest;
import com.rentalshop.backend.entity.Booking;
import com.rentalshop.backend.entity.Customer;
import com.rentalshop.backend.entity.Item;
import com.rentalshop.backend.exception.BookingConflictException;
import com.rentalshop.backend.repository.BookingRepository;
import com.rentalshop.backend.repository.CustomerRepository;
import com.rentalshop.backend.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Booking creation is the single highest-risk piece of business logic in the
 * whole system (development plan, Section 3.8 / 14.5 / 14.9): a double-booking
 * bug directly costs the shop money and trust. This service is intentionally
 * built and tested in isolation, ahead of any booking UI, per the recommended
 * build order.
 *
 * Two independent safety nets are layered here, matching the plan exactly:
 *
 *   1. Idempotency key (unique DB constraint on bookings.idempotency_key) —
 *      protects against the SAME logical request being retried by the app
 *      after a timeout (e.g. cold-start delay on Aiven/Render free tier).
 *
 *   2. Pessimistic row lock on the item + a fresh overlap query inside the
 *      SAME transaction — protects against TWO DIFFERENT booking requests
 *      for the same item racing each other. This is the part a naive
 *      "check then insert" implementation gets wrong: without the lock, two
 *      concurrent requests can both pass the overlap check (because neither
 *      has committed yet) and both insert, silently double-booking the item.
 */
@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final ItemRepository itemRepository;
    private final CustomerRepository customerRepository;
    private final AuditLogService auditLogService;

    private static final DateTimeFormatter BOOKING_NUMBER_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Creates a booking, or returns the existing one if this exact
     * idempotencyKey was already processed successfully.
     *
     * Isolation.READ_COMMITTED is explicit here (MySQL's InnoDB default is
     * REPEATABLE READ) because the correctness of this method does NOT rely on
     * snapshot isolation — it relies entirely on the explicit pessimistic lock
     * below, which works correctly under READ_COMMITTED and avoids extra
     * gap-lock/next-key-lock surprises that REPEATABLE READ can introduce
     * under high concurrency on this access pattern.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse createBooking(CreateBookingRequest req) {

        // --- Idempotency check first: if this exact attempt already
        // succeeded, return the prior result instead of re-processing.
        var existing = bookingRepository.findByIdempotencyKey(req.getIdempotencyKey());
        if (existing.isPresent()) {
            return BookingResponse.from(existing.get());
        }

        if (req.getReturnDate().isBefore(req.getPickupDate())) {
            throw new IllegalArgumentException("returnDate cannot be before pickupDate");
        }

        // --- Acquire the row lock on the item BEFORE checking for overlaps.
        // Any other transaction trying to book (or lock) this same item will
        // block here until this transaction commits or rolls back.
        Item item = itemRepository.findByIdForUpdate(req.getItemId())
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + req.getItemId()));

        if (item.isDeleted() || item.getStatus() != Item.ItemStatus.ACTIVE) {
            throw new IllegalStateException("Item is not currently bookable: " + item.getItemCode());
        }

        Customer customer = customerRepository.findById(req.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + req.getCustomerId()));

        // --- Now that we hold the lock, re-check for overlaps. This read is
        // guaranteed fresh relative to any other transaction that already
        // committed a booking for this item, because that transaction must
        // have released the lock (via commit) before we acquired it.
        List<Booking> conflicts = bookingRepository.findOverlapping(
                item.getId(), req.getPickupDate(), req.getReturnDate(), -1L);

        if (!conflicts.isEmpty()) {
            throw new BookingConflictException(
                    "Item " + item.getItemCode() + " is not available for " +
                    req.getPickupDate() + " to " + req.getReturnDate() +
                    " (conflicts with booking " + conflicts.get(0).getBookingNumber() + ")");
        }

        Booking booking = new Booking();
        booking.setBookingNumber(generateBookingNumber(item));
        booking.setItem(item);
        booking.setCustomer(customer);
        booking.setPickupDate(req.getPickupDate());
        booking.setEventDate(req.getEventDate());
        booking.setReturnDate(req.getReturnDate());
        booking.setRentalAmount(req.getRentalAmount());
        booking.setDepositAmount(req.getDepositAmount());
        booking.setAdvanceAmount(req.getAdvanceAmount());
        booking.setBalanceAmount(computeBalance(req));
        booking.setStatus(Booking.BookingStatus.CONFIRMED);
        booking.setNotes(req.getNotes());
        booking.setIdempotencyKey(req.getIdempotencyKey());
        booking.setCreatedBy(req.getCreatedBy());

        Booking saved = bookingRepository.save(booking);

        // Audit log + the Layer-3 redundant trail (Telegram/Sheets async write)
        // both happen from here, AFTER save() but still inside this transaction
        // boundary for the audit log; the Telegram/Sheets call itself should be
        // fired as a post-commit async event (see AuditLogService / TrailNotifier
        // — wire an ApplicationEventPublisher here in the full implementation so
        // a slow Telegram API can never block or fail the booking transaction).
        auditLogService.recordBookingCreated(saved);

        return BookingResponse.from(saved);
    }

    /**
     * Moves a booking through its lifecycle (Section: booking status
     * transitions). This is what actually frees an item's calendar back up
     * -- BookingRepository's overlap query already excludes RETURNED and
     * CANCELLED, but nothing could reach either status until this existed,
     * so every booking effectively blocked its dates forever.
     *
     * Same version-first optimistic-lock pattern as ItemService.updateItem:
     * req.getVersion() is applied to the entity before the transition check,
     * so a stale write raises OptimisticLockException (-> 409 STALE_WRITE)
     * exactly like a concurrent item edit would.
     *
     * Returns empty if no booking exists with this id, so the controller can
     * map that to 404 the same way ItemService.updateItem does.
     */
    @Transactional
    public Optional<BookingResponse> updateStatus(Long id, UpdateBookingStatusRequest req) {
        return bookingRepository.findById(id)
                .map(booking -> {
                    booking.setVersion(req.getVersion());

                    Booking.BookingStatus previousStatus = booking.getStatus();
                    Booking.BookingStatus target = req.getTargetStatus();

                    if (!previousStatus.canTransitionTo(target)) {
                        throw new IllegalStateException(
                                "Cannot transition booking " + booking.getBookingNumber() +
                                " from " + previousStatus + " to " + target);
                    }

                    booking.setStatus(target);
                    Booking saved = bookingRepository.save(booking);
                    auditLogService.recordBookingStatusChanged(saved, previousStatus);
                    return BookingResponse.from(saved);
                });
    }

    /**
     * GET /api/bookings. Thin pass-through to BookingRepository.search --
     * see that query's doc for the filter semantics. No @RequireRole at
     * this layer (that's the controller's job); every filter combination
     * is readable by both Admin and Viewer devices, same as item/customer lists.
     */
    @Transactional(readOnly = true)
    public List<BookingResponse> listBookings(Booking.BookingStatus status, Long itemId,
                                               Long customerId, LocalDate dueOnOrBefore) {
        return bookingRepository.search(status, itemId, customerId, dueOnOrBefore).stream()
                .map(BookingResponse::from)
                .toList();
    }

    /**
     * Item-wise Calendar screen: every booking that occupies this item's
     * calendar (PENDING/CONFIRMED/PICKED_UP -- see BookingStatus#occupiesCalendar)
     * overlapping [start, end], soonest pickup first. Deliberately reuses
     * findOverlapping (the exact same query booking-creation already relies
     * on to detect conflicts) rather than a second bespoke query -- if this
     * ever disagreed with what BookingService.createBooking considers "booked",
     * the calendar screen would show dates as free that the create endpoint
     * would then reject, which is a worse bug than a little query reuse.
     *
     * Returns empty if no non-deleted item exists with this id, so the
     * controller can map that to 404 the same way item lookups already do.
     */
    @Transactional(readOnly = true)
    public Optional<List<BookingResponse>> getItemCalendar(Long itemId, LocalDate start, LocalDate end) {
        return itemRepository.findById(itemId)
                .filter(item -> !item.isDeleted())
                .map(item -> bookingRepository.findOverlapping(itemId, start, end, -1L).stream()
                        .sorted((a, b) -> a.getPickupDate().compareTo(b.getPickupDate()))
                        .map(BookingResponse::from)
                        .toList());
    }

    private BigDecimal computeBalance(CreateBookingRequest req) {
        return req.getRentalAmount()
                .add(req.getDepositAmount() == null ? BigDecimal.ZERO : req.getDepositAmount())
                .subtract(req.getAdvanceAmount() == null ? BigDecimal.ZERO : req.getAdvanceAmount());
    }

    @Transactional(readOnly = true)
    public Optional<BookingResponse> getBooking(Long id) {
        return bookingRepository.findByIdWithDetails(id).map(BookingResponse::from);
    }

    private String generateBookingNumber(Item item) {
        String datePart = LocalDate.now().format(BOOKING_NUMBER_DATE_FMT);
        String shortRandom = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return "BK-" + datePart + "-" + shortRandom;
    }
}
