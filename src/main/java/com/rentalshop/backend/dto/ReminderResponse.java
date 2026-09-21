package com.rentalshop.backend.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything the app's scheduled reminders need, in one call -- see
 * ReminderController. Each list is already filtered, grouped into bills and
 * sorted, and is never null (an empty list means "nothing to remind about",
 * which the app takes as "don't show a notification").
 *
 * @param date         "today" as the SERVER sees it, in the shop's own time
 *                     zone (app.reminders.zone) -- not the JVM's, which on a
 *                     cloud host is usually UTC.
 * @param pickupsToday bills with items due to go out today, not yet picked up.
 * @param returnsDue   bills with items still out whose return date is today
 *                     or already past (see ReminderBill.daysOverdue).
 * @param paymentsDue  bills returned past their return date but not settled
 *                     full and final -- the same bills as Amount Due Bills.
 */
public record ReminderResponse(
        LocalDate date,
        List<ReminderBill> pickupsToday,
        List<ReminderBill> returnsDue,
        List<ReminderBill> paymentsDue
) {
}
