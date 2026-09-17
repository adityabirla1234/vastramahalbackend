package com.rentalshop.backend.service.trail;

import java.util.List;

/**
 * Swappable Google Sheets-delivery boundary -- same rationale as
 * {@link TelegramSender}: one real implementation, but RedundantTrailService
 * stays decoupled from it.
 */
public interface SheetsAppender {

    /**
     * Appends one row to the configured spreadsheet/sheet. {@code row} is
     * a flat list of cell values in column order; the caller decides the
     * columns, this boundary just appends them. Implementations throw on
     * any failure -- same "caller records the error and retries" contract
     * as {@link TelegramSender#send}.
     */
    void appendRow(List<String> row);
}
