package com.rentalshop.backend.repository;

import com.rentalshop.backend.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Booking Details screen's payment history — oldest first, matching a receipt/ledger read. */
    List<Payment> findByBookingIdOrderByPaymentDateAscCreatedAtAsc(Long bookingId);

    /**
     * Every payment taken against any item of one bill, oldest first --
     * backs the bill-level payment history on the group booking screen.
     * Traverses booking.groupId rather than taking a groupId column of its
     * own, keeping "a group is just the rows that share the value" true
     * here too (see Booking.groupId's Javadoc).
     */
    List<Payment> findByBooking_GroupIdOrderByPaymentDateAscCreatedAtAsc(String groupId);

    /** The rows that made up one bill-level payment -- see Payment.groupPaymentRef. */
    List<Payment> findByGroupPaymentRef(String groupPaymentRef);
}
