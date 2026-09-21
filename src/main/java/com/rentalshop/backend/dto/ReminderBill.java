package com.rentalshop.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One bill as it appears in a reminder notification -- a standalone booking,
 * or a whole group booking collapsed into a single entry (the same "one bill
 * = one row" rule the app's Bookings list and Amount Due Bills already use).
 *
 * Only the rows that belong to THIS reminder are counted: for "pickups
 * today" that is the items still waiting to go out, for "returns due" the
 * items still out, for "payment due" the returned rows still owing money. So
 * a bill can legitimately show up in two reminders at once (one item back
 * and unpaid, another still out).
 *
 * @param reference   what to print next to "Bill": the shop's own Bill No,
 *                    or the system booking number for older bookings that
 *                    never had one -- never null, so a notification always
 *                    has something to show.
 * @param billNumber  the raw Bill No as staff typed it; null if none.
 * @param itemCount   rows of this bill covered by the reminder.
 * @param pickupDate  earliest pickup date among those rows.
 * @param returnDate  earliest (i.e. most overdue) return date among those rows.
 * @param daysOverdue whole days between returnDate and today; 0 when it is
 *                    due today (or not yet due, as for a pickup reminder).
 * @param amountDue   sum of balanceAmount over those rows.
 * @param bookingId   lowest booking id in the reminder, for deep-linking.
 * @param groupId     the bill's groupId; null for a standalone booking.
 */
public record ReminderBill(
        String reference,
        String billNumber,
        String customerName,
        String customerPhone,
        int itemCount,
        LocalDate pickupDate,
        LocalDate returnDate,
        long daysOverdue,
        BigDecimal amountDue,
        Long bookingId,
        String groupId
) {
}
