package com.rentalshop.backend.repository;

import com.rentalshop.backend.entity.Item;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long> {

    Optional<Item> findByItemCodeAndDeletedFalse(String itemCode);

    /**
     * Checked on create against ALL items, including soft-deleted ones --
     * item_code has a hard unique constraint in the schema regardless of
     * is_deleted, so a retired code must stay reserved.
     */
    boolean existsByItemCode(String itemCode);

    /**
     * Row-level lock on the item being booked. Every booking-creation
     * transaction acquires this lock FIRST, before checking for date overlaps.
     * This is what actually prevents the race condition where two concurrent
     * requests both read "no conflict" and both insert a booking for the
     * same overlapping range — MySQL's default REPEATABLE READ isolation
     * does NOT prevent that on its own for a read-then-insert pattern.
     *
     * Because every booking transaction for a given item always takes this
     * lock first, concurrent booking attempts on the SAME item are serialized;
     * attempts on DIFFERENT items are unaffected and run fully in parallel.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Item i where i.id = :id")
    Optional<Item> findByIdForUpdate(@Param("id") Long id);

    List<Item> findByDeletedFalseAndStatus(Item.ItemStatus status);

    /**
     * Section 3.6: returns only items with NO overlapping active booking for
     * the ENTIRE requested range (not partial availability). category is
     * optional — pass null to search across all categories.
     *
     * NOT EXISTS is used rather than a NOT IN subquery so it behaves
     * correctly even if the booking subquery could return NULLs, and so the
     * query planner can use the idx_booking_item_dates index for the
     * correlated lookup per item.
     */
    @Query("""
           select i from Item i
           where i.deleted = false
             and i.status = 'ACTIVE'
             and (:category is null or i.category = :category)
             and not exists (
                 select 1 from Booking b
                 where b.item = i
                   and b.status in ('PENDING', 'CONFIRMED', 'PICKED_UP')
                   and b.pickupDate <= :requestedEnd
                   and b.returnDate >= :requestedStart
             )
           order by i.name
           """)
    List<Item> findAvailableInRange(@Param("requestedStart") LocalDate requestedStart,
                                     @Param("requestedEnd") LocalDate requestedEnd,
                                     @Param("category") String category);

    /**
     * Backs the inventory list screen. category and status are both
     * optional (pass null to not filter on them); includeDeleted defaults
     * to false everywhere except an explicit admin "show retired items" view.
     */
    @Query("""
           select i from Item i
           where (:includeDeleted = true or i.deleted = false)
             and (:category is null or i.category = :category)
             and (:status is null or i.status = :status)
           order by i.name
           """)
    List<Item> search(@Param("category") String category,
                       @Param("status") Item.ItemStatus status,
                       @Param("includeDeleted") boolean includeDeleted);
}
