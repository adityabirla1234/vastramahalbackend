package com.rentalshop.backend.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-time data cleanup, run on every startup (cheap when there is nothing
 * to do): cancelling a booking now deletes it permanently
 * (BookingService.deleteBookingPermanently), but rows that were cancelled
 * under the OLD behaviour are still sitting in the table with status
 * CANCELLED and would keep showing up in the app. This removes them -- and
 * their payments, accessories and audit_logs rows -- for good, in one
 * transaction so a failure part-way leaves the data untouched.
 *
 * Irreversible by design. Set app.purge-cancelled-bookings=false to turn it
 * off (e.g. to take a database backup first).
 */
@Component
public class CancelledBookingPurgeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CancelledBookingPurgeRunner.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;

    public CancelledBookingPurgeRunner(JdbcTemplate jdbcTemplate,
                                       TransactionTemplate transactionTemplate,
                                       @Value("${app.purge-cancelled-bookings:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            Integer purged = transactionTemplate.execute(status -> purge());
            if (purged != null && purged > 0) {
                log.info("Cancelled-booking purge: permanently deleted {} cancelled booking row(s) and everything attached to them", purged);
            }
        } catch (Exception e) {
            // Never block startup; the next startup simply retries.
            log.warn("Cancelled-booking purge did not complete; will retry on next startup", e);
        }
    }

    private int purge() {
        // Audit rows first -- they are located through the booking/payment
        // rows that the later statements delete.
        jdbcTemplate.update("""
                DELETE FROM audit_logs
                WHERE entity_type = 'PAYMENT'
                  AND entity_id IN (SELECT p.id FROM payments p
                                    JOIN bookings b ON b.id = p.booking_id
                                    WHERE b.status = 'CANCELLED')
                """);
        jdbcTemplate.update("""
                DELETE FROM audit_logs
                WHERE entity_type = 'BOOKING'
                  AND entity_id IN (SELECT b.id FROM bookings b WHERE b.status = 'CANCELLED')
                """);
        jdbcTemplate.update("""
                DELETE FROM payments
                WHERE booking_id IN (SELECT b.id FROM bookings b WHERE b.status = 'CANCELLED')
                """);
        jdbcTemplate.update("""
                DELETE FROM booking_accessories
                WHERE booking_id IN (SELECT b.id FROM bookings b WHERE b.status = 'CANCELLED')
                """);
        return jdbcTemplate.update("DELETE FROM bookings WHERE status = 'CANCELLED'");
    }
}
