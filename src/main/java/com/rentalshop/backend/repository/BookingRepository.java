package com.rentalshop.backend.repository;

import com.rentalshop.backend.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    Optional<Booking> findByBookingNumber(String bookingNumber);
    @Query("""
       select b from Booking b
       join fetch b.item
       join fetch b.customer
       where b.id = :id
       """)
    Optional<Booking> findByIdWithDetails(@Param("id") Long id);

    /**
     * Section 3.8 overlap rule: existingStart <= requestedEnd AND existingEnd >= requestedStart.
     * Only bookings in a calendar-occupying status are considered (Cancelled bookings
     * release their dates immediately per Section 14, rule 6; Returned bookings don't
     * block future dates per rule 7).
     *
     * excludeBookingId is used when re-validating an existing booking's own date
     * edit, so it doesn't conflict with itself. Pass -1L (or any impossible id)
     * when creating a brand-new booking.
     */
    @Query("""
           select b from Booking b
           where b.item.id = :itemId
             and b.status in ('PENDING', 'CONFIRMED', 'PICKED_UP')
             and b.id <> :excludeBookingId
             and b.pickupDate <= :requestedEnd
             and b.returnDate >= :requestedStart
           """)
    List<Booking> findOverlapping(@Param("itemId") Long itemId,
                                   @Param("requestedStart") LocalDate requestedStart,
                                   @Param("requestedEnd") LocalDate requestedEnd,
                                   @Param("excludeBookingId") Long excludeBookingId);

    /**
     * Section 3.6 availability search: items with NO overlapping booking in the
     * requested range are available. Used as an anti-join from the item side —
     * see AvailabilityService (not included in this scaffold) for the full query
     * that combines this with the items table.
     */
    @Query("""
           select b.item.id from Booking b
           where b.status in ('PENDING', 'CONFIRMED', 'PICKED_UP')
             and b.pickupDate <= :requestedEnd
             and b.returnDate >= :requestedStart
           """)
    List<Long> findBookedItemIdsInRange(@Param("requestedStart") LocalDate requestedStart,
                                         @Param("requestedEnd") LocalDate requestedEnd);

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
}
