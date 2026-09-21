package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.ReminderBill;
import com.rentalshop.backend.service.ReminderBuilder.Row;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderBuilderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    private static Row row(long id, String billNo, String groupId, String customer,
                           LocalDate pickup, LocalDate ret, String balance) {
        return new Row(id, "BK-" + id, billNo, groupId, customer, "9999900000", pickup, ret, new BigDecimal(balance));
    }

    private static List<String> refs(List<ReminderBill> bills) {
        return bills.stream().map(ReminderBill::reference).toList();
    }

    @Test
    void groupBookingIsOneBillWithItemsCountedAndBalancesSummed() {
        LocalDate ret = TODAY.minusDays(2);
        List<ReminderBill> bills = ReminderBuilder.paymentsDue(List.of(
                row(1, "1042", "g-1", "Sita", TODAY.minusDays(5), ret, "1500.00"),
                row(2, "1042", "g-1", "Sita", TODAY.minusDays(5), ret, "1000.50"),
                row(3, "1043", null, "Amit", TODAY.minusDays(6), ret, "700.00")), TODAY);

        assertEquals(2, bills.size());
        ReminderBill group = bills.stream().filter(b -> "1042".equals(b.reference())).findFirst().orElseThrow();
        assertEquals(2, group.itemCount());
        assertEquals(0, new BigDecimal("2500.50").compareTo(group.amountDue()));
        assertEquals("g-1", group.groupId());
        assertEquals(Long.valueOf(1L), group.bookingId());
    }

    @Test
    void billWithoutABillNumberFallsBackToTheBookingNumber() {
        List<ReminderBill> bills = ReminderBuilder.pickups(List.of(
                row(7, null, null, "Ravi", TODAY, TODAY.plusDays(2), "0"),
                row(8, "   ", null, "Meena", TODAY, TODAY.plusDays(2), "0")), TODAY);

        assertEquals(List.of("BK-7", "BK-8"), refs(bills));
        assertNull(bills.get(0).billNumber());
        assertNull(bills.get(1).billNumber());
    }

    @Test
    void aBlankRowDoesNotHideTheBillNumberOfItsSibling() {
        List<ReminderBill> bills = ReminderBuilder.pickups(List.of(
                row(1, null, "g-9", "Kavita", TODAY, TODAY.plusDays(1), "0"),
                row(2, " 2210 ", "g-9", "Kavita", TODAY, TODAY.plusDays(1), "0")), TODAY);

        assertEquals(1, bills.size());
        assertEquals("2210", bills.get(0).reference());
    }

    @Test
    void returnsAreCountedInDaysAndListedMostOverdueFirst() {
        List<ReminderBill> bills = ReminderBuilder.returnsDue(List.of(
                row(1, "A", null, "Due today", TODAY.minusDays(3), TODAY, "0"),
                row(2, "B", null, "Three late", TODAY.minusDays(9), TODAY.minusDays(3), "0"),
                row(3, "C", null, "One late", TODAY.minusDays(4), TODAY.minusDays(1), "0")), TODAY);

        assertEquals(List.of("B", "C", "A"), refs(bills));
        assertEquals(3, bills.get(0).daysOverdue());
        assertEquals(1, bills.get(1).daysOverdue());
        assertEquals(0, bills.get(2).daysOverdue());
    }

    @Test
    void aPartlyOverdueGroupIsAsOverdueAsItsEarliestItem() {
        List<ReminderBill> bills = ReminderBuilder.returnsDue(List.of(
                row(1, "5001", "g-2", "Neha", TODAY.minusDays(8), TODAY.minusDays(1), "0"),
                row(2, "5001", "g-2", "Neha", TODAY.minusDays(8), TODAY.minusDays(4), "0")), TODAY);

        assertEquals(1, bills.size());
        assertEquals(2, bills.get(0).itemCount());
        assertEquals(TODAY.minusDays(4), bills.get(0).returnDate());
        assertEquals(4, bills.get(0).daysOverdue());
    }

    @Test
    void pickupDateBeforeTodayNeverProducesNegativeOverdueDays() {
        List<ReminderBill> bills = ReminderBuilder.pickups(List.of(
                row(1, "1", null, "X", TODAY, TODAY.plusDays(3), "0")), TODAY);

        assertEquals(0, bills.get(0).daysOverdue());
    }

    @Test
    void billNumbersSortNumericallyThenAsText() {
        List<Row> rows = new ArrayList<>();
        long id = 1;
        for (String no : List.of("100", "A1", "9", "10", "1a", "007")) {
            rows.add(row(id++, no, null, "C", TODAY, TODAY, "0"));
        }

        assertEquals(List.of("007", "9", "10", "100", "1a", "A1"), refs(ReminderBuilder.pickups(rows, TODAY)));
    }

    @Test
    void billNumberComparisonIsAConsistentTotalOrder() {
        // Comparing numbers numerically but a number against text as text
        // is NOT one: 9 < 10 (numeric), 10 < 1a (text), yet 1a < 9 (text) is
        // a cycle, and List.sort can throw "Comparison method violates its
        // general contract" on a long enough list. Check the property itself
        // -- antisymmetry and transitivity over a spread of shapes -- rather
        // than hoping a particular sort run trips over it.
        List<String> refs = List.of("0", "007", "7", "9", "10", "100", "1000", "19", "1a", "1A", "9a",
                "10a", "A1", "a1", "A10", "B", "b", "-5", "12-B", "12b", "BK-7", "1/2", "");
        for (String a : refs) {
            for (String b : refs) {
                int ab = Integer.signum(ReminderBuilder.compareReferences(a, b));
                int ba = Integer.signum(ReminderBuilder.compareReferences(b, a));
                assertEquals(ab, -ba);
                for (String c : refs) {
                    if (ab <= 0 && ReminderBuilder.compareReferences(b, c) <= 0) {
                        assertTrue(ReminderBuilder.compareReferences(a, c) <= 0);
                    }
                }
            }
        }
    }

    @Test
    void emptyInputGivesEmptyLists() {
        assertTrue(ReminderBuilder.pickups(List.of(), TODAY).isEmpty());
        assertTrue(ReminderBuilder.returnsDue(List.of(), TODAY).isEmpty());
        assertTrue(ReminderBuilder.paymentsDue(List.of(), TODAY).isEmpty());
    }
}
