package com.rentalshop.backend.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Response for POST /api/bookings/batch. [groupId] is the value every
 * successfully-created row in [results] was tagged with -- the app keeps
 * this so a failed row can be fixed and resubmitted alone (via the plain
 * POST /api/bookings, with groupId set to this same value) and still join
 * the same group. [results] is in the same order as the request's items.
 */
@Getter
@Builder
public class BookingBatchResponse {
    private String groupId;
    private List<BookingItemResult> results;

    public long successCount() {
        return results.stream().filter(BookingItemResult::isSuccess).count();
    }

    public long failureCount() {
        return results.size() - successCount();
    }

    public boolean hasAnyFailure() {
        return failureCount() > 0;
    }

    public boolean allSucceeded() {
        return failureCount() == 0;
    }
}
