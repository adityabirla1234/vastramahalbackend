package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.BookingBatchResponse;
import com.rentalshop.backend.dto.BookingItemResult;
import com.rentalshop.backend.dto.BookingResponse;
import com.rentalshop.backend.dto.CreateBookingBatchRequest;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    /**
     * Self-injected proxy (standard Spring self-injection pattern, @Lazy to
     * break the circular dependency). createBookingBatch below is NOT
     * @Transactional itself -- it calls self.createBooking(...) once per
     * item, and because that call goes back through the Spring proxy, each
     * call opens and commits/rolls back its OWN transaction. That's what
     * makes one item's conflict independent of the others: item #4 failing
     * rolls back only item #4's transaction, never touching #1-3's already-
     * committed rows (Section 3.7 / 14.9 partial-success rule). Calling
     * this.createBooking(...) directly instead would silently skip
     * @Transactional entirely (no self-proxying in plain Java), which is
     * the mistake this field exists to avoid.
     */
    @Autowired
    @Lazy
    private BookingService self;

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
        // depositAmount deliberately left unset here -- it defaults to ZERO
        // on the entity and is only ever set later, at pickup time (see
        // updateStatus() below).
        booking.setAdvanceAmount(req.getAdvanceAmount());
        booking.setBalanceAmount(computeBalance(req));
        booking.setStatus(Booking.BookingStatus.CONFIRMED);
        booking.setNotes(req.getNotes());
        booking.setIdempotencyKey(req.getIdempotencyKey());
        booking.setCreatedBy(req.getCreatedBy());
        booking.setGroupId(req.getGroupId());

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
     * Section 3.7 multi-item single-form booking: creates every item in
     * [req.getItems()] tagged with the SAME groupId. Deliberately NOT
     * @Transactional itself -- each item is created via self.createBooking(...)
     * (see the `self` field's Javadoc above), so a conflict or validation
     * failure on one item can never roll back items that already committed.
     * This is what lets staff fix just the failing row's dates and resubmit
     * it alone (via the plain POST /api/bookings, same groupId) instead of
     * losing the whole group.
     *
     * groupId resolution: if the FIRST item in the batch already carries a
     * non-blank groupId (e.g. the whole batch is being retried wholesale
     * after a timeout/crash before any response came back), that value is
     * reused for every item so the retry rejoins the same group rather than
     * starting a second one. Every already-succeeded item in that retry
     * short-circuits via its own idempotencyKey inside createBooking, so
     * nothing is duplicated. Otherwise a fresh UUID is generated here and
     * stamped onto every item before it's created.
     */
    public BookingBatchResponse createBookingBatch(CreateBookingBatchRequest req) {
        String firstGroupId = req.getItems().isEmpty() ? null : req.getItems().get(0).getGroupId();
        String groupId = (firstGroupId != null && !firstGroupId.isBlank())
                ? firstGroupId
                : UUID.randomUUID().toString();

        List<BookingItemResult> results = new ArrayList<>();
        for (CreateBookingRequest item : req.getItems()) {
            item.setGroupId(groupId);
            try {
                BookingResponse response = self.createBooking(item);
                results.add(BookingItemResult.ok(response));
            } catch (BookingConflictException e) {
                results.add(BookingItemResult.failed(item.getItemId(), "BOOKING_CONFLICT", e.getMessage()));
            } catch (IllegalArgumentException e) {
                results.add(BookingItemResult.failed(item.getItemId(), "BAD_REQUEST", e.getMessage()));
            } catch (IllegalStateException e) {
                results.add(BookingItemResult.failed(item.getItemId(), "INVALID_STATE", e.getMessage()));
            } catch (RuntimeException e) {
                // Anything unexpected still gets captured as a per-item failure
                // rather than aborting the rest of the batch (e.g. a transient
                // DB hiccup on just this one item shouldn't cost the others).
                results.add(BookingItemResult.failed(item.getItemId(), "UNKNOWN_ERROR", e.getMessage()));
            }
        }

        return BookingBatchResponse.builder()
                .groupId(groupId)
                .results(results)
                .build();
    }

    /**
     * Section 3.7 multi-item booking: every row sharing one groupId, for
     * the "overall bill" view. See BookingRepository.findByGroupIdWithDetails.
     */
    @Transactional(readOnly = true)
    public List<BookingResponse> getGroupBookings(String groupId) {
        return bookingRepository.findByGroupIdWithDetails(groupId).stream()
                .map(BookingResponse::from)
                .toList();
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

                    // Section 3.7 redesign, step 5: the security deposit is
                    // collected right here, at pickup time -- never at
                    // creation. The app must prompt for it before calling
                    // this with targetStatus=PICKED_UP; enforced server-side
                    // too so a client bug can't silently skip it. It folds
                    // into balanceAmount the same way rentalAmount/advanceAmount
                    // already do at creation, replacing the ZERO placeholder
                    // that's been sitting there since the booking was made.
                    if (target == Booking.BookingStatus.PICKED_UP) {
                        if (req.getDepositAmount() == null) {
                            throw new IllegalArgumentException(
                                    "depositAmount is required when marking a booking as picked up");
                        }
                        BigDecimal previousDeposit = booking.getDepositAmount() == null
                                ? BigDecimal.ZERO : booking.getDepositAmount();
                        booking.setDepositAmount(req.getDepositAmount());
                        booking.setBalanceAmount(
                                booking.getBalanceAmount().subtract(previousDeposit).add(req.getDepositAmount()));
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

    // Deposit is intentionally excluded here -- at creation time it's always
    // ZERO (see createBooking above), so including it would be a no-op today,
    // but leaving it out of the formula entirely (rather than adding a ZERO)
    // keeps this method honest about what booking creation actually charges.
    // Once updateStatus() sets a real depositAmount at pickup time, that
    // value flows into balanceAmount there, not here.
    private BigDecimal computeBalance(CreateBookingRequest req) {
        return req.getRentalAmount()
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
