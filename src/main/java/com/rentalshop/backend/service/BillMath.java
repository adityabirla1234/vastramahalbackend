package com.rentalshop.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Money arithmetic for {@link BillActionService}, kept free of Spring and JPA
 * so it can be tested on its own -- the rounding here is the one place in the
 * pickup flow where an off-by-a-paisa bug would silently make a bill's
 * deposit total disagree with what the customer actually handed over.
 */
final class BillMath {

    private BillMath() {
    }

    /**
     * Splits [total] into [parts] shares that are each at most two decimal
     * places and add back up to EXACTLY [total].
     *
     * The security deposit is entered once for everything going out, but the
     * app stores it per booking row (Booking.depositAmount) because a bill
     * has no entity of its own. Dividing it evenly keeps every picked-up row
     * showing a sensible figure; the bill total is the sum of the rows, so
     * that sum must equal what was typed in. Each share is the equal share
     * rounded DOWN, and whatever paise are left over are handed out one at a
     * time to the first shares -- e.g. 1000.00 over 3 rows is 333.34, 333.33,
     * 333.33.
     */
    static List<BigDecimal> splitEvenly(BigDecimal total, int parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException("parts must be positive");
        }
        BigDecimal exact = total.setScale(2, RoundingMode.HALF_UP);
        BigDecimal count = BigDecimal.valueOf(parts);
        BigDecimal base = exact.divide(count, 2, RoundingMode.DOWN);
        BigDecimal leftover = exact.subtract(base.multiply(count));
        int extraPaise = leftover.movePointRight(2).intValueExact();

        BigDecimal paisa = new BigDecimal("0.01");
        List<BigDecimal> shares = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            shares.add(i < extraPaise ? base.add(paisa) : base);
        }
        return shares;
    }

    /** Sum of [amounts], treating an empty list as zero. */
    static BigDecimal sum(List<BigDecimal> amounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            total = total.add(amount);
        }
        return total;
    }
}
