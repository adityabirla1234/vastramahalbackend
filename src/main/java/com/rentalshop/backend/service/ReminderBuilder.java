package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.ReminderBill;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns booking rows into the bills a reminder lists. Kept free of Spring and
 * JPA (same reason as {@link BillMath}) so the grouping, the day arithmetic
 * and the ordering can be tested on their own -- {@link ReminderService} does
 * the querying and maps each Booking entity to a {@link Row}.
 *
 * A bill is every row sharing a groupId, or a lone row when there is none.
 * That is deliberately looser than the app's "2+ rows" rule for opening the
 * group screen: a reminder only prints a Bill No and never opens the bill, so
 * a one-item group and a standalone booking are the same thing here.
 */
final class ReminderBuilder {

    private ReminderBuilder() {
    }

    /** The handful of booking fields a reminder needs, detached from JPA. */
    record Row(
            Long id,
            String bookingNumber,
            String billNumber,
            String groupId,
            String customerName,
            String customerPhone,
            LocalDate pickupDate,
            LocalDate returnDate,
            BigDecimal balanceAmount
    ) {
    }

    /** Pickups reminder: ordered by Bill No so the list reads like the shop's bill book. */
    static List<ReminderBill> pickups(List<Row> rows, LocalDate today) {
        return build(rows, today, Comparator.comparing(ReminderBill::reference, ReminderBuilder::compareReferences));
    }

    /** Returns reminder: most overdue first, since that's the one to chase. */
    static List<ReminderBill> returnsDue(List<Row> rows, LocalDate today) {
        return build(rows, today, MOST_OVERDUE_FIRST);
    }

    /** Payment reminder: longest-unpaid first. */
    static List<ReminderBill> paymentsDue(List<Row> rows, LocalDate today) {
        return build(rows, today, MOST_OVERDUE_FIRST);
    }

    private static final Comparator<ReminderBill> MOST_OVERDUE_FIRST =
            Comparator.comparing(ReminderBill::returnDate)
                    .thenComparing(ReminderBill::reference, ReminderBuilder::compareReferences);

    private static List<ReminderBill> build(List<Row> rows, LocalDate today, Comparator<ReminderBill> order) {
        // LinkedHashMap: keeps first-seen order, which the final sort then
        // refines -- so ties always fall back to something stable.
        Map<String, List<Row>> bills = new LinkedHashMap<>();
        for (Row row : rows) {
            bills.computeIfAbsent(billKey(row), k -> new ArrayList<>()).add(row);
        }
        List<ReminderBill> result = new ArrayList<>(bills.size());
        for (List<Row> billRows : bills.values()) {
            result.add(toBill(billRows, today));
        }
        result.sort(order.thenComparing(ReminderBill::bookingId));
        return result;
    }

    private static String billKey(Row row) {
        String groupId = row.groupId();
        return groupId != null && !groupId.isBlank() ? "g:" + groupId : "b:" + row.id();
    }

    private static ReminderBill toBill(List<Row> rows, LocalDate today) {
        Row first = rows.get(0);

        // One bill = one number, stamped on every row of the group -- but
        // take the first NON-blank one so a stray blank row can't hide it.
        String billNumber = null;
        for (Row row : rows) {
            if (row.billNumber() != null && !row.billNumber().isBlank()) {
                billNumber = row.billNumber().trim();
                break;
            }
        }

        LocalDate pickup = first.pickupDate();
        LocalDate ret = first.returnDate();
        BigDecimal due = BigDecimal.ZERO;
        long lowestId = first.id();
        for (Row row : rows) {
            if (row.pickupDate().isBefore(pickup)) {
                pickup = row.pickupDate();
            }
            if (row.returnDate().isBefore(ret)) {
                ret = row.returnDate();
            }
            if (row.balanceAmount() != null) {
                due = due.add(row.balanceAmount());
            }
            lowestId = Math.min(lowestId, row.id());
        }

        String reference = billNumber != null ? billNumber : first.bookingNumber();
        long overdue = Math.max(0, ChronoUnit.DAYS.between(ret, today));
        String groupId = first.groupId() != null && !first.groupId().isBlank() ? first.groupId() : null;

        return new ReminderBill(
                reference, billNumber, first.customerName(), first.customerPhone(),
                rows.size(), pickup, ret, overdue, due, lowestId, groupId);
    }

    /**
     * Bill Nos are free text, but in practice they're mostly plain numbers,
     * and a plain string sort puts "100" before "9". So: all-digit values
     * come first, ordered numerically (compared as digit strings, so a very
     * long one can't overflow); everything else follows, ordered as
     * case-insensitive text.
     *
     * The "numbers first, then text" split is what keeps this a consistent
     * ordering. Comparing two numbers numerically but a number against text
     * as text would not be -- "9" < "10" < "1a" < "9" is a cycle -- and
     * List.sort can throw "Comparison method violates its general contract"
     * on a long enough list when handed a comparator like that.
     */
    static int compareReferences(String a, String b) {
        boolean aNumeric = isDigits(a);
        boolean bNumeric = isDigits(b);
        if (aNumeric != bNumeric) {
            return aNumeric ? -1 : 1;
        }
        if (aNumeric) {
            String x = stripLeadingZeros(a);
            String y = stripLeadingZeros(b);
            if (x.length() != y.length()) {
                return Integer.compare(x.length(), y.length());
            }
            int byDigits = x.compareTo(y);
            if (byDigits != 0) {
                return byDigits;
            }
        }
        return String.CASE_INSENSITIVE_ORDER.compare(a, b);
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }
}
