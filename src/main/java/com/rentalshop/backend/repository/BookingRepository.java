package com.rentalshop.backend.repository;

import com.rentalshop.backend.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    /** Blocks a hard delete of an item that still has booking history referencing it. */
    boolean existsByItemId(Long itemId);

    /** Blocks a hard delete of a customer that still has booking history referencing it. */
    boolean existsByCustomerId(Long customerId);

    Optional<Booking> findByBookingNumber(String bookingNumber);
    @Query("""
       select b from Booking b
       join fetch b.item
       join fetch b.customer
       where b.id = :id
       """)
    Optional<Booking> findByIdWithDetails(@Param("id") Long id);

    /**
     * Section 3.8 overlap rule, now down to the minute:
     * existingStart <= requestedEnd AND existingEnd >= requestedStart, where
     * each side is a date + time. Both ends are inclusive, so an item due back
     * 29 Jan 13:30 conflicts with a request starting 13:30 but NOT one
     * starting 13:31.
     *
     * Only bookings in a calendar-occupying status are considered (Cancelled bookings
     * release their dates immediately per Section 14, rule 6; Returned bookings don't
     * block future dates per rule 7).
     *
     * excludeBookingId is used when re-validating an existing booking's own date
     * edit, so it doesn't conflict with itself. Pass -1L (or any impossible id)
     * when creating a brand-new booking.
     */
    default List<Booking> findOverlapping(Long itemId, LocalDateTime requestedStart,
                                          LocalDateTime requestedEnd, Long excludeBookingId) {
        return findOverlappingParts(itemId,
                requestedStart.toLocalDate(), requestedStart.toLocalTime(),
                requestedEnd.toLocalDate(), requestedEnd.toLocalTime(),
                excludeBookingId);
    }

    @Query("""
           select b from Booking b
           where b.item.id = :itemId
             and b.status in ('PENDING', 'CONFIRMED', 'PICKED_UP')
             and b.id <> :excludeBookingId
             and (b.pickupDate < :endDate or (b.pickupDate = :endDate and b.pickupTime <= :endTime))
             and (b.returnDate > :startDate or (b.returnDate = :startDate and b.returnTime >= :startTime))
           """)
    List<Booking> findOverlappingParts(@Param("itemId") Long itemId,
                                       @Param("startDate") LocalDate startDate,
                                       @Param("startTime") LocalTime startTime,
                                       @Param("endDate") LocalDate endDate,
                                       @Param("endTime") LocalTime endTime,
                                       @Param("excludeBookingId") Long excludeBookingId);

    /**
     * Section 3.6 availability search: items with NO overlapping booking in the
     * requested range are available. Same minute-level rule as findOverlapping.
     */
    default List<Long> findBookedItemIdsInRange(LocalDateTime requestedStart, LocalDateTime requestedEnd) {
        return findBookedItemIdsInRangeParts(
                requestedStart.toLocalDate(), requestedStart.toLocalTime(),
                requestedEnd.toLocalDate(), requestedEnd.toLocalTime());
    }

    @Query("""
           select b.item.id from Booking b
           where b.status in ('PENDING', 'CONFIRMED', 'PICKED_UP')
             and (b.pickupDate < :endDate or (b.pickupDate = :endDate and b.pickupTime <= :endTime))
             and (b.returnDate > :startDate or (b.returnDate = :startDate and b.returnTime >= :startTime))
           """)
    List<Long> findBookedItemIdsInRangeParts(@Param("startDate") LocalDate startDate,
                                             @Param("startTime") LocalTime startTime,
                                             @Param("endDate") LocalDate endDate,
                                             @Param("endTime") LocalTime endTime);

    /**
     * Backs GET /api/bookings (All Bookings / Returns / Item Calendar /
     * Customer History screens -- none built yet, but all four need the
     * same filtered list underneath). Every filter is optional: a null
     * parameter is matched with `:param is null or ...`, so passing all
     * nulls returns every booking, sorted soonest-pickup-first.
     *
     * [dueOnOrBefore] is specifically for a "Returns due" view: bookings
     * still occupying the calendar (not yet RETURNED/CANCELLED) whose
     * returnDate has arrived or passed. Left null for a plain filtered list.
     */
    @Query("""
       select b from Booking b
       join fetch b.item
       join fetch b.customer
       where (:status is null or b.status = :status)
         and (:itemId is null or b.item.id = :itemId)
         and (:customerId is null or b.customer.id = :customerId)
         and (:dueOnOrBefore is null or
              (b.status in ('PENDING', 'CONFIRMED', 'PICKED_UP') and b.returnDate <= :dueOnOrBefore))
       order by b.pickupDate asc
       """)
    List<Booking> search(@Param("status") Booking.BookingStatus status,
                         @Param("itemId") Long itemId,
                         @Param("customerId") Long customerId,
                         @Param("dueOnOrBefore") LocalDate dueOnOrBefore);

    /**
     * Section 3.7 multi-item booking: every row sharing one groupId,
     * soonest pickup first. Backs GET /api/bookings/group/{groupId} --
     * the "overall bill" for a multi-item booking session is just the sum
     * of these on the client, computed on the fly rather than stored
     * anywhere (there is no separate booking-group entity).
     */
    @Query("""
       select b from Booking b
       join fetch b.item
       join fetch b.customer
       where b.groupId = :groupId
       order by b.pickupDate asc
       """)
    List<Booking> findByGroupIdWithDetails(@Param("groupId") String groupId);

    /**
     * How many item rows one bill has. Used to tell a real multi-item bill
     * apart from a one-item booking that merely carries a groupId (New
     * Booking always submits through the batch endpoint, so every booking
     * made by the app has one) -- the distinction that decides whether
     * payments belong on the bill or on the item. A count rather than
     * reusing findByGroupIdWithDetails: the callers only need the number,
     * not four joined entity graphs.
     */
    long countByGroupId(String groupId);

    /**
     * Reminder: bookings due to go out on [date] that haven't yet. [statuses]
     * is the pre-pickup set (PENDING/CONFIRMED) -- passed in rather than
     * written as string literals so the enum, not a spelling, is what's
     * matched. Only the customer is fetched: a reminder never touches the
     * item or the accessories.
     */
    @Query("""
       select b from Booking b
       join fetch b.customer
       where b.status in :statuses
         and b.pickupDate = :date
       order by b.id asc
       """)
    List<Booking> findPickupsOn(@Param("statuses") Collection<Booking.BookingStatus> statuses,
                                @Param("date") LocalDate date);

    /**
     * Reminder: rows in [status] (PICKED_UP -- physically out) whose return
     * date is today or already gone by. Unlike search(dueOnOrBefore), this
     * never includes rows that haven't been picked up yet.
     */
    @Query("""
       select b from Booking b
       join fetch b.customer
       where b.status = :status
         and b.returnDate <= :today
       order by b.id asc
       """)
    List<Booking> findOutOnOrPastReturnDate(@Param("status") Booking.BookingStatus status,
                                            @Param("today") LocalDate today);

    /**
     * Reminder: returned rows still owing money past their return date --
     * status RETURNED + settlementStatus DUE (the Amount Due Bills rule), a
     * return date strictly before [today], and a balance actually left to
     * collect (a DUE row with nothing owing has nothing to remind about).
     */
    @Query("""
       select b from Booking b
       join fetch b.customer
       where b.status = :status
         and b.settlementStatus = :settlement
         and b.returnDate < :today
         and b.balanceAmount > 0
       order by b.id asc
       """)
    List<Booking> findUnsettledPastReturnDate(@Param("status") Booking.BookingStatus status,
                                              @Param("settlement") Booking.SettlementStatus settlement,
                                              @Param("today") LocalDate today);
}
