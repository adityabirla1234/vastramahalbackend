package com.rentalshop.backend.repository;

import com.rentalshop.backend.entity.BookingAccessory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingAccessoryRepository extends JpaRepository<BookingAccessory, Long> {

    /**
     * Blocks a hard delete of an item that a past booking recorded as an
     * accessory (see BookingAccessory's class doc) -- item_id has a plain
     * FK with no ON DELETE clause in schema.sql, so the DB would reject the
     * delete anyway; this lets ItemService give a clear 409 instead of a
     * raw constraint-violation error.
     */
    boolean existsByItemId(Long itemId);
}
