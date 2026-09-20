package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.AddBookingItemRequest;
import com.rentalshop.backend.dto.BookingAccessoryRequest;
import com.rentalshop.backend.dto.BookingBatchResponse;
import com.rentalshop.backend.dto.BookingItemResult;
import com.rentalshop.backend.dto.BookingResponse;
import com.rentalshop.backend.dto.CreateBookingBatchRequest;
import com.rentalshop.backend.dto.CreateBookingRequest;
import com.rentalshop.backend.dto.UpdateBookingItemRequest;
import com.rentalshop.backend.dto.UpdateBookingStatusRequest;
import com.rentalshop.backend.entity.AccessoryCategory;
import com.rentalshop.backend.entity.Booking;
import com.rentalshop.backend.entity.BookingAccessory;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        booking.setAdvancePaymentMethod(normalizeAdvancePaymentMethod(req));
        booking.setBalanceAmount(computeBalance(req));
        booking.setStatus(Booking.BookingStatus.CONFIRMED);
        booking.setNotes(req.getNotes());
        booking.setIdempotencyKey(req.getIdempotencyKey());
        booking.setCreatedBy(req.getCreatedBy());
        booking.setGroupId(req.getGroupId());
        booking.setBillNumber(normalizeBillNumber(req.getBillNumber()));

        // Attached BEFORE save so the cascade writes the accessory rows in
        // the same insert batch as the booking itself -- one transaction,
        // so a booking can never end up committed with half its accessory
        // list, and a rejected accessory (below) rolls the booking back
        // too rather than silently dropping what staff ticked.
        attachAccessories(booking, req.getAccessories());

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

        // One bill, one Bill No: take the first non-blank value any item
        // carries and stamp it onto every item, same "never trust per-row
        // inconsistency" reasoning as groupId above. A retry of a partly-
        // failed batch re-sends the same value (the app locks the field
        // once anything may have committed), so retried rows rejoin the
        // same bill number as the rows that already went through.
        String billNumber = req.getItems().stream()
                .map(r -> normalizeBillNumber(r.getBillNumber()))
                .filter(b -> b != null)
                .findFirst()
                .orElse(null);

        List<BookingItemResult> results = new ArrayList<>();
        for (CreateBookingRequest item : req.getItems()) {
            item.setGroupId(groupId);
            item.setBillNumber(billNumber);
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
                    // too so a client bug can't silently skip it. It is
                    // recorded on the booking for display only -- it is
                    // deliberately NEVER folded into rentalAmount or
                    // balanceAmount, at this transition or any other. The
                    // security deposit is shown to staff/customer but is not
                    // part of the bill they're charged.
                    if (target == Booking.BookingStatus.PICKED_UP) {
                        if (req.getDepositAmount() == null) {
                            throw new IllegalArgumentException(
                                    "depositAmount is required when marking a booking as picked up");
                        }
                        booking.setDepositAmount(req.getDepositAmount());
                        // Optional on this legacy endpoint (older app builds
                        // don't send it); the current app goes through
                        // BillActionService.markPickedUp, where it's required.
                        String depositMethod = req.getDepositPaymentMethod();
                        booking.setDepositPaymentMethod(
                                req.getDepositAmount().signum() > 0 && depositMethod != null && !depositMethod.isBlank()
                                        ? depositMethod.trim() : null);
                    }

                    // Marking an item RETURNED is the moment the deposit is
                    // handed back (or not) and the bill is settled -- but
                    // only once, for the WHOLE bill. A standalone booking's
                    // bill is just itself; a group booking's bill is every
                    // row sharing its groupId, so this only fires once every
                    // sibling has already reached a terminal status (i.e.
                    // this is the last item in the group still to be
                    // returned). Everything before that point is a plain
                    // status transition with no deposit/settlement decision
                    // attached -- the app doesn't even show that prompt yet.
                    if (target == Booking.BookingStatus.RETURNED) {
                        List<Booking> groupSiblings = booking.getGroupId() == null
                                ? List.of()
                                : bookingRepository.findByGroupIdWithDetails(booking.getGroupId()).stream()
                                        .filter(sibling -> !sibling.getId().equals(booking.getId()))
                                        .toList();

                        boolean isFinalReturn = groupSiblings.stream()
                                .allMatch(sibling -> sibling.getStatus() == Booking.BookingStatus.RETURNED
                                        || sibling.getStatus() == Booking.BookingStatus.CANCELLED);

                        if (isFinalReturn) {
                            if (req.getDepositReturnStatus() == null || req.getSettlementStatus() == null) {
                                throw new IllegalArgumentException(
                                        "depositReturnStatus and settlementStatus are required when returning " +
                                        "the last item of a bill");
                            }

                            applySettlement(booking, req);
                            for (Booking sibling : groupSiblings) {
                                // Only RETURNED siblings actually had a deposit
                                // collected / a balance worth settling -- a
                                // CANCELLED sibling never got that far, so it's
                                // left untouched other than the version bump
                                // it already carries from its own transition.
                                if (sibling.getStatus() == Booking.BookingStatus.RETURNED) {
                                    applySettlement(sibling, req);
                                    bookingRepository.save(sibling);
                                }
                            }
                        }
                    }

                    booking.setStatus(target);
                    Booking saved = bookingRepository.save(booking);
                    auditLogService.recordBookingStatusChanged(saved, previousStatus);
                    return BookingResponse.from(saved);
                });
    }

    /**
     * Amount Due Bills (Customers section): closes out a bill that was left
     * DUE at final return, once staff has since collected the outstanding
     * balance in full. Only callable on a bill that is actually sitting in
     * that state -- RETURNED with settlementStatus DUE -- anything else
     * (still open, already SETTLED, never reached a final-return decision
     * at all) is rejected with IllegalStateException (-> 409) rather than
     * silently no-opping.
     *
     * [id] can be ANY booking belonging to the bill -- for a standalone
     * booking that's the only choice; for a group booking, passing any one
     * sibling settles every RETURNED/DUE row sharing its groupId in the
     * same transaction, mirroring how updateStatus's final-return handling
     * already cascades a settlement decision across the whole group. Only
     * [id]'s own row is version-checked (same optimistic-lock contract as
     * updateStatus) -- siblings are updated without a version check, same
     * as updateStatus already does for them, since they're only ever
     * touched here as a direct consequence of settling the one bill they
     * belong to, not edited independently.
     *
     * Returns empty if no booking exists with this id, so the controller
     * can map that to 404 the same way updateStatus does.
     */
    @Transactional
    public Optional<BookingResponse> settleBill(Long id, Long version) {
        return bookingRepository.findById(id).map(booking -> {
            if (booking.getStatus() != Booking.BookingStatus.RETURNED
                    || booking.getSettlementStatus() != Booking.SettlementStatus.DUE) {
                throw new IllegalStateException(
                        "Booking " + booking.getBookingNumber() + " is not pending settlement");
            }

            booking.setVersion(version);
            booking.setSettlementStatus(Booking.SettlementStatus.SETTLED);
            booking.setBalanceAmount(BigDecimal.ZERO);
            Booking saved = bookingRepository.save(booking);
            auditLogService.recordBillSettled(saved);

            if (booking.getGroupId() != null) {
                List<Booking> dueSiblings = bookingRepository.findByGroupIdWithDetails(booking.getGroupId()).stream()
                        .filter(sibling -> !sibling.getId().equals(booking.getId())
                                && sibling.getStatus() == Booking.BookingStatus.RETURNED
                                && sibling.getSettlementStatus() == Booking.SettlementStatus.DUE)
                        .toList();
                for (Booking sibling : dueSiblings) {
                    sibling.setSettlementStatus(Booking.SettlementStatus.SETTLED);
                    sibling.setBalanceAmount(BigDecimal.ZERO);
                    bookingRepository.save(sibling);
                    auditLogService.recordBillSettled(sibling);
                }
            }

            return BookingResponse.from(saved);
        });
    }

    /**
     * The advance's payment method, trimmed -- or null when there is nothing
     * to record: no advance was taken, or the client sent a blank value.
     */
    private String normalizeAdvancePaymentMethod(CreateBookingRequest req) {
        BigDecimal advance = req.getAdvanceAmount();
        if (advance == null || advance.signum() <= 0) {
            return null;
        }
        String method = req.getAdvancePaymentMethod();
        return method == null || method.isBlank() ? null : method.trim();
    }

    /**
     * Records the deposit-return / settlement decision on one booking row.
     * SETTLED clears whatever balance is left on that row to zero (the bill
     * is closed full-and-final); DUE leaves balanceAmount exactly as it
     * stood. Never touches depositAmount or rentalAmount -- the deposit was
     * never part of the bill, so returning it doesn't change the bill either.
     */
    private void applySettlement(Booking booking, UpdateBookingStatusRequest req) {
        booking.setDepositReturnStatus(req.getDepositReturnStatus());
        booking.setSettlementStatus(req.getSettlementStatus());
        if (req.getSettlementStatus() == Booking.SettlementStatus.SETTLED) {
            booking.setBalanceAmount(BigDecimal.ZERO);
        }
    }

    /**
     * Booking History "Edit bill" action: edits ONE item row of an existing
     * bill in place -- exchanging it for a different inventory item,
     * correcting the rental/advance amount, and/or replacing its notes and
     * accessories. See UpdateBookingItemRequest's Javadoc for the
     * "omitted field means leave it alone" contract each field follows.
     *
     * Refused once the row is closed out (RETURNED/CANCELLED) -- there is
     * nothing left to edit on a bill that's done, and quietly "fixing" a
     * closed bill would make Billing History lie about what the customer
     * was actually charged when they picked up. An item exchange is refused
     * earlier still, at PICKED_UP, since the physical garment has already
     * left the shop by then; notes/accessories/amount corrections remain
     * allowed at PICKED_UP (Section: staff commonly need to fix a mistyped
     * rental amount after the fact, even on an item that's already out).
     *
     * balanceAmount is always re-derived from the row's own rental/advance
     * after every edit, mirroring computeBalance's formula exactly -- so an
     * edit that only touched, say, notes still leaves the balance correct
     * (it's simply recomputed to the same value), and one that touched the
     * rental amount can never leave a stale balance behind.
     *
     * Returns empty if no booking exists with this id, so the controller
     * can map that to 404 the same way updateStatus does.
     */
    @Transactional
    public Optional<BookingResponse> updateBookingItem(Long id, UpdateBookingItemRequest req) {
        return bookingRepository.findByIdWithDetails(id).map(booking -> {
            if (booking.getStatus() == Booking.BookingStatus.RETURNED
                    || booking.getStatus() == Booking.BookingStatus.CANCELLED) {
                throw new IllegalStateException(
                        "Booking " + booking.getBookingNumber() + " is closed and can no longer be edited");
            }

            booking.setVersion(req.getVersion());

            // Captured BEFORE any of the request's changes are applied --
            // see AuditLogService.recordBookingItemUpdated's Javadoc.
            java.util.Map<String, Object> before = auditLogService.snapshotBooking(booking);

            if (req.getItemId() != null && !req.getItemId().equals(booking.getItem().getId())) {
                exchangeItem(booking, req.getItemId());
            }

            if (req.getRentalAmount() != null) {
                booking.setRentalAmount(req.getRentalAmount());
            }
            if (req.getAdvanceAmount() != null) {
                booking.setAdvanceAmount(req.getAdvanceAmount());
            }
            if (req.getNotes() != null) {
                booking.setNotes(req.getNotes());
            }
            if (req.getFittingWork() != null) {
                // Trimmed, and blank collapses to null: "no fitting work"
                // has exactly one representation, so the app's
                // Add-vs-View decision is a plain null check and a
                // whitespace-only save can't leave a "View fitting work"
                // button opening an empty dialog.
                String fittingWork = req.getFittingWork().strip();
                booking.setFittingWork(fittingWork.isEmpty() ? null : fittingWork);
            }
            if (req.getAccessories() != null) {
                // orphanRemoval=true on Booking.accessories: clearing the
                // live collection (rather than reassigning it) issues real
                // deletes for whatever was there before, so a full replace
                // is exactly this clear-then-reattach, never a merge.
                booking.getAccessories().clear();
                attachAccessories(booking, req.getAccessories());
            }

            booking.setBalanceAmount(booking.getRentalAmount().subtract(
                    booking.getAdvanceAmount() == null ? BigDecimal.ZERO : booking.getAdvanceAmount()));

            Booking saved = bookingRepository.save(booking);
            auditLogService.recordBookingItemUpdated(before, saved);
            return BookingResponse.from(saved);
        });
    }

    /**
     * The "Exchange" half of {@link #updateBookingItem}: swaps [booking]
     * onto a different inventory item, keeping its existing pickup/return
     * dates. Only reachable while the row is still PENDING or CONFIRMED --
     * once PICKED_UP the physical garment is already out the door, so
     * there is nothing left in the shop to swap it for.
     *
     * Same two-step safety net booking creation uses: a pessimistic lock
     * on the target item, then a fresh overlap check inside this same
     * transaction, excluding [booking]'s own id so it never conflicts with
     * itself.
     */
    private void exchangeItem(Booking booking, Long newItemId) {
        if (booking.getStatus() != Booking.BookingStatus.PENDING
                && booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
            throw new IllegalStateException(
                    "Item " + booking.getItem().getItemCode() + " has already been picked up " +
                    "and can no longer be exchanged");
        }

        Item newItem = itemRepository.findByIdForUpdate(newItemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + newItemId));

        if (newItem.isDeleted() || newItem.getStatus() != Item.ItemStatus.ACTIVE) {
            throw new IllegalStateException("Item is not currently bookable: " + newItem.getItemCode());
        }

        List<Booking> conflicts = bookingRepository.findOverlapping(
                newItem.getId(), booking.getPickupDate(), booking.getReturnDate(), booking.getId());
        if (!conflicts.isEmpty()) {
            throw new BookingConflictException(
                    "Item " + newItem.getItemCode() + " is not available for " +
                    booking.getPickupDate() + " to " + booking.getReturnDate() +
                    " (conflicts with booking " + conflicts.get(0).getBookingNumber() + ")");
        }

        booking.setItem(newItem);
    }

    /**
     * Booking History "Remove item" action: takes ONE item row off a bill.
     * Never a hard delete -- the row is transitioned to CANCELLED (freeing
     * its dates on the item's calendar, same as any other cancellation) and
     * its billable amounts are zeroed, so it drops out of every bill total
     * that folds over the group's rows (BillTotalsSection on the app side,
     * BookingService.getGroupBookings's callers) without erasing the fact
     * that it was once booked -- the audit log and the row itself (still
     * visible, badged CANCELLED, in the bill's item list) keep that history.
     *
     * For a standalone booking (no groupId -- a "booking per row"), this is
     * simply the whole booking going away: there's only ever the one row,
     * so removing "an item from the bill" and cancelling the bill outright
     * are the same action, and the existing CANCELLED semantics already
     * cover it correctly.
     *
     * Refused once the row is already closed out (RETURNED/CANCELLED,
     * including a double-tap of this same action) or PICKED_UP -- an item
     * that has physically left the shop can't be waved away from a bill; it
     * has to come back through the normal return flow first.
     *
     * Returns empty if no booking exists with this id, so the controller
     * can map that to 404 the same way updateStatus does.
     */
    @Transactional
    public Optional<BookingResponse> removeBookingItem(Long id, Long version) {
        return bookingRepository.findByIdWithDetails(id).map(booking -> {
            if (booking.getStatus() != Booking.BookingStatus.PENDING
                    && booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
                throw new IllegalStateException(
                        "Booking " + booking.getBookingNumber() + " cannot be removed from the bill in its " +
                        "current status (" + booking.getStatus() + ")");
            }

            booking.setVersion(version);

            java.util.Map<String, Object> before = auditLogService.snapshotBooking(booking);

            booking.setStatus(Booking.BookingStatus.CANCELLED);
            booking.setRentalAmount(BigDecimal.ZERO);
            booking.setAdvanceAmount(BigDecimal.ZERO);
            booking.setBalanceAmount(BigDecimal.ZERO);

            Booking saved = bookingRepository.save(booking);
            auditLogService.recordBookingItemRemoved(before, saved);
            return BookingResponse.from(saved);
        });
    }

    /**
     * Booking History "Add item to bill" action: creates a brand-new row on
     * the same bill [anchorBookingId] belongs to. customerId, groupId and
     * billNumber are always resolved from the anchor -- never trusted from
     * the client -- exactly the same posture createBookingBatch already
     * applies to a fresh group.
     *
     * If the anchor is still a standalone booking (groupId null -- a
     * "booking per row"), this is the moment it becomes a group: a fresh
     * UUID is generated and stamped onto the anchor itself before the new
     * row is created with the same value, mirroring createBookingBatch's
     * own groupId resolution for a brand-new multi-item session. That
     * stamp is a system-assigned bookkeeping field, not something the
     * device that loaded the anchor could have gone stale on, so it's
     * applied without an optimistic-lock check -- the same reasoning
     * createBookingBatch already relies on when it stamps groupId/billNumber
     * onto every row of a batch.
     *
     * Refused if the anchor's bill is already closed out
     * (RETURNED/CANCELLED) -- nothing more should be added to a bill that's
     * done. Delegates the new row itself to createBooking (via the [self]
     * proxy, same as createBookingBatch) so it gets every safety net a
     * brand-new booking gets: the idempotency check, the pessimistic lock,
     * and the overlap re-check.
     *
     * Returns empty if no booking exists with id [anchorBookingId], so the
     * controller can map that to 404 the same way updateStatus does.
     */
    @Transactional
    public Optional<BookingResponse> addItemToBill(Long anchorBookingId, AddBookingItemRequest req, Long createdBy) {
        Optional<Booking> anchorOpt = bookingRepository.findByIdWithDetails(anchorBookingId);
        if (anchorOpt.isEmpty()) {
            return Optional.empty();
        }
        Booking anchor = anchorOpt.get();

        if (anchor.getStatus() == Booking.BookingStatus.RETURNED
                || anchor.getStatus() == Booking.BookingStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Booking " + anchor.getBookingNumber() + " is closed and can no longer have items added to it");
        }

        String groupId = anchor.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            groupId = UUID.randomUUID().toString();
            anchor.setGroupId(groupId);
            bookingRepository.save(anchor);
        }

        CreateBookingRequest createRequest = new CreateBookingRequest();
        createRequest.setItemId(req.getItemId());
        createRequest.setCustomerId(anchor.getCustomer().getId());
        createRequest.setPickupDate(req.getPickupDate());
        createRequest.setEventDate(req.getEventDate());
        createRequest.setReturnDate(req.getReturnDate());
        createRequest.setRentalAmount(req.getRentalAmount());
        createRequest.setAdvanceAmount(req.getAdvanceAmount());
        createRequest.setNotes(req.getNotes());
        createRequest.setAccessories(req.getAccessories());
        createRequest.setIdempotencyKey(req.getIdempotencyKey());
        createRequest.setCreatedBy(createdBy);
        createRequest.setGroupId(groupId);
        createRequest.setBillNumber(anchor.getBillNumber());

        return Optional.of(self.createBooking(createRequest));
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

    /**
     * Copies the "Add accessory" selections for one item row onto its
     * Booking as snapshot rows (see BookingAccessory's class doc for why
     * they're snapshots rather than live joins).
     *
     * Three rules worth knowing:
     *
     *   1. Duplicates of the same itemId are COLLAPSED, not rejected. The
     *      picker lets staff tick the same accessory twice (across two
     *      category tabs, or on a double tap) and failing an otherwise-good
     *      booking over that would be a terrible trade. The DB's
     *      uq_booking_accessory constraint is the backstop if this ever
     *      slips.
     *   2. An itemId that doesn't resolve to a live inventory item IS a
     *      hard failure (IllegalArgumentException -> 400 / BAD_REQUEST on
     *      this row only, per the batch's partial-success rule). Silently
     *      dropping it would hand staff a bill missing a line they ticked,
     *      which is exactly the kind of quiet data loss that erodes trust
     *      in the app.
     *   3. The bucket is resolved from the item's OWN category/sub-category
     *      first and only falls back to what the client sent -- the server
     *      trusts inventory over the client, but won't block a booking just
     *      because an item was filed under a spelling
     *      AccessoryCategory.matchTerms() doesn't know yet.
     *
     * Deliberately does NOT check the accessory's availability or create a
     * Booking row for it: an accessory is recorded as "this went out with
     * that dress", not as a separately-booked item. See BookingAccessory's
     * class doc for what to do instead if that ever needs to change.
     */
    private void attachAccessories(Booking booking, List<BookingAccessoryRequest> requested) {
        if (requested == null || requested.isEmpty()) {
            return;
        }

        Set<Long> alreadyAttached = new LinkedHashSet<>();
        for (BookingAccessoryRequest accessoryRequest : requested) {
            if (accessoryRequest == null || accessoryRequest.getItemId() == null) {
                continue;
            }
            if (!alreadyAttached.add(accessoryRequest.getItemId())) {
                continue; // rule 1: same accessory ticked twice
            }

            Item accessoryItem = itemRepository.findById(accessoryRequest.getItemId())
                    .filter(i -> !i.isDeleted())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Accessory item not found: " + accessoryRequest.getItemId()));

            AccessoryCategory category = AccessoryCategory
                    .match(accessoryItem.getCategory(), accessoryItem.getSubCategory())
                    .orElse(accessoryRequest.getCategory());
            if (category == null) {
                throw new IllegalArgumentException(
                        "Could not determine the accessory category for item " + accessoryItem.getItemCode());
            }

            BookingAccessory accessory = new BookingAccessory();
            accessory.setItemId(accessoryItem.getId());
            accessory.setItemCode(accessoryItem.getItemCode());
            accessory.setItemName(accessoryItem.getName());
            accessory.setCategory(category);
            booking.addAccessory(accessory);
        }
    }

    /** Trims; a blank Bill No is stored as null rather than an empty string. */
    private static String normalizeBillNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String generateBookingNumber(Item item) {
        String datePart = LocalDate.now().format(BOOKING_NUMBER_DATE_FMT);
        String shortRandom = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return "BK-" + datePart + "-" + shortRandom;
    }
}
