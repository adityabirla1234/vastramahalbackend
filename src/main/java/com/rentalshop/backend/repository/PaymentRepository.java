package com.rentalshop.backend.repository;

import com.rentalshop.backend.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Booking Details screen's payment history — oldest first, matching a receipt/ledger read. */
    List<Payment> findByBookingIdOrderByPaymentDateAscCreatedAtAsc(Long bookingId);
}
