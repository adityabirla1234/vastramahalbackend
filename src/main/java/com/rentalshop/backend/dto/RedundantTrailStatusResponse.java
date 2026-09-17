package com.rentalshop.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Backs a future System Health (Admin) screen's Layer-3 section -- same
 * "read-only, admin-only" posture as AuditLogController. Counts only, not
 * a row dump: an admin needs to know "is the redundant trail keeping up",
 * not page through every entry (the entries table itself is queryable
 * directly for that, same as audit_logs).
 */
@Getter
@AllArgsConstructor
public class RedundantTrailStatusResponse {
    private boolean telegramEnabled;
    private boolean sheetsEnabled;
    private long telegramSent;
    private long telegramPending;
    private long telegramFailed;
    private long sheetsSent;
    private long sheetsPending;
    private long sheetsFailed;
}
