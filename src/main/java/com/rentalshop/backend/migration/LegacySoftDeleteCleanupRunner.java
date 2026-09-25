package com.rentalshop.backend.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time data migration, run automatically on every startup. This project
 * doesn't run schema.sql (spring.sql.init.mode=never, and it's a raw file
 * nobody applies by hand either) -- ddl-auto=update handles everything that
 * fits "add a column/table Hibernate hasn't seen yet", but it will never
 * drop a column or clean up data on its own, so anything in that second
 * category has to happen from app code instead. This class is that.
 *
 * Items/customers switched from soft delete to a real hard DELETE (see
 * ItemService.deleteItem / CustomerService.deleteCustomer). Anything that
 * was soft-deleted under the OLD code (is_deleted=true) is still sitting in
 * the table as a real row, and item_code/name have always been enforced
 * unique against every row regardless of is_deleted -- so a retired
 * item_code or a retired customer stays stuck and unreusable until that
 * row is actually removed, which is what surfaces as a 409 on Add Item /
 * Add Customer for a code that "shouldn't" be taken.
 *
 * This purges every such row that has no booking history (safe -- nothing
 * references it, same as a fresh call to the new delete endpoint would do
 * for it today). A soft-deleted row that DOES still have booking history is
 * deliberately left in place: the new delete endpoint would refuse to
 * remove it too, for the same referential-integrity reason, so its
 * item_code/name stays reserved on purpose -- pick a different one for the
 * new item/customer.
 *
 * Cheap and safe to run on every startup: it checks is_deleted still exists
 * before doing anything, so after the first run (or on a DB that never had
 * the column) this is a single information_schema query and nothing else.
 */
@Component
public class LegacySoftDeleteCleanupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacySoftDeleteCleanupRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public LegacySoftDeleteCleanupRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            cleanUpItems();
            cleanUpCustomers();
        } catch (Exception e) {
            // Never block app startup over a cleanup step -- worst case, an
            // old item_code/customer stays stuck and someone hits the same
            // 409 this migration exists to fix, which is exactly where
            // things already stood before this class existed.
            log.warn("Legacy soft-delete cleanup did not complete; will retry on next startup", e);
        }
    }

    private void cleanUpItems() {
        if (!columnExists("items", "is_deleted")) {
            return;
        }
        int purged = jdbcTemplate.update("""
                DELETE items FROM items
                LEFT JOIN bookings b ON b.item_id = items.id
                LEFT JOIN booking_accessories ba ON ba.item_id = items.id
                WHERE items.is_deleted = TRUE
                  AND b.id IS NULL
                  AND ba.id IS NULL
                """);
        if (purged > 0) {
            log.info("Legacy soft-delete cleanup: removed {} stale item row(s) with no booking history, freeing up their item_code(s)", purged);
        }
        dropColumnIfExists("items", "is_deleted");
    }

    private void cleanUpCustomers() {
        if (!columnExists("customers", "is_deleted")) {
            return;
        }
        int purged = jdbcTemplate.update("""
                DELETE customers FROM customers
                LEFT JOIN bookings b ON b.customer_id = customers.id
                WHERE customers.is_deleted = TRUE
                  AND b.id IS NULL
                """);
        if (purged > 0) {
            log.info("Legacy soft-delete cleanup: removed {} stale customer row(s) with no booking history", purged);
        }
        dropColumnIfExists("customers", "is_deleted");
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        return count != null && count > 0;
    }

    private void dropColumnIfExists(String table, String column) {
        // Best-effort tidy-up now that nothing reads/writes this column --
        // not required for the fix above (the DELETE already ran), so a
        // missing ALTER privilege here is fine to just log and move past.
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
            log.info("Legacy soft-delete cleanup: dropped now-unused {}.{} column", table, column);
        } catch (Exception e) {
            log.info("Legacy soft-delete cleanup: left {}.{} column in place ({})", table, column, e.getMessage());
        }
    }
}
