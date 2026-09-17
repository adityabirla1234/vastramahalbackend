package com.rentalshop.backend.service;

import com.rentalshop.backend.entity.RedundantTrailEntry;
import com.rentalshop.backend.entity.RedundantTrailEntry.DeliveryStatus;
import com.rentalshop.backend.repository.RedundantTrailEntryRepository;
import com.rentalshop.backend.service.trail.SheetsAppender;
import com.rentalshop.backend.service.trail.TelegramSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Layer 3 of the plan: an external, out-of-database trail of every
 * mutation (Telegram messages, Google Sheet rows) so the shop's data can
 * be reconstructed even if the primary Aiven MySQL instance is lost
 * entirely -- Layer 1 is the DB itself, Layer 2 is the GitHub Actions
 * backup pipeline (still not built), this is Layer 3.
 *
 * Hooked from AuditLogService.write() (see that class's javadoc) rather
 * than called by individual services directly, for the same "one choke
 * point" reason PushSyncService is. Two phases per mutation:
 * <ol>
 *   <li>{@link #enqueue} -- runs INSIDE the caller's transaction (same as
 *       the audit_logs insert itself) and just persists a
 *       RedundantTrailEntry row. A local DB insert carries no risk of
 *       blocking/failing the real mutation, unlike a network call.</li>
 *   <li>{@link #deliverAsync} -- runs post-commit, off-thread, and does
 *       the actual Telegram/Sheets network calls. Never allowed to affect
 *       the original request in any way; failures are recorded on the row
 *       and picked up again by {@link #retryFailedDeliveries}.</li>
 * </ol>
 */
@Service
public class RedundantTrailService {

    private static final Logger log = LoggerFactory.getLogger(RedundantTrailService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Telegram message length cap (4096) leaves room to spare -- before/after snapshots are truncated well under it. */
    private static final int SNAPSHOT_PREVIEW_CHARS = 400;
    private static final int LAST_ERROR_MAX_CHARS = 500;

    private final RedundantTrailEntryRepository repository;
    private final TelegramSender telegramSender;
    private final SheetsAppender sheetsAppender;
    private final boolean telegramEnabled;
    private final boolean sheetsEnabled;
    private final int maxAttempts;

    public RedundantTrailService(
            RedundantTrailEntryRepository repository,
            TelegramSender telegramSender,
            SheetsAppender sheetsAppender,
            @Value("${app.redundant-trail.telegram.enabled:false}") boolean telegramEnabled,
            @Value("${app.redundant-trail.sheets.enabled:false}") boolean sheetsEnabled,
            @Value("${app.redundant-trail.retry.max-attempts:5}") int maxAttempts) {
        this.repository = repository;
        this.telegramSender = telegramSender;
        this.sheetsAppender = sheetsAppender;
        this.telegramEnabled = telegramEnabled;
        this.sheetsEnabled = sheetsEnabled;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Persists a queue row for this mutation, or does nothing at all when
     * both channels are off -- matches the zero-config default posture
     * (see NoOpTelegramSender/NoOpSheetsAppender javadocs) rather than
     * growing a table full of DISABLED rows nobody will ever look at.
     * MUST be called from within the caller's own transaction (same one
     * the audit_logs row is written in) -- no @Transactional here on
     * purpose, since Spring's self-invocation proxying wouldn't apply a
     * new one anyway when called from AuditLogService.write() in-process.
     *
     * @return the new row's id, or null if nothing was queued.
     */
    public Long enqueue(String action, String entityType, Long entityId, Long actorId,
                         String beforeStateJson, String afterStateJson) {
        if (!telegramEnabled && !sheetsEnabled) {
            return null;
        }

        RedundantTrailEntry entry = new RedundantTrailEntry();
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setActorId(actorId);
        entry.setBeforeState(beforeStateJson);
        entry.setAfterState(afterStateJson);
        entry.setTelegramStatus(telegramEnabled ? DeliveryStatus.PENDING : DeliveryStatus.DISABLED);
        entry.setSheetsStatus(sheetsEnabled ? DeliveryStatus.PENDING : DeliveryStatus.DISABLED);
        return repository.save(entry).getId();
    }

    /**
     * Attempts delivery on whichever channels are still PENDING for this
     * row. Runs off the request thread -- see AuditLogService's post-commit
     * hook, which is the only caller in normal operation; the scheduled
     * sweep below is the other. A missing/already-fully-delivered row is a
     * silent no-op (e.g. a stale sweep id for a row someone else already finished).
     */
    @Async
    @Transactional
    public void deliverAsync(Long entryId) {
        if (entryId == null) {
            return;
        }
        repository.findById(entryId).ifPresent(entry -> {
            attemptTelegram(entry);
            attemptSheets(entry);
            repository.save(entry);
        });
    }

    /**
     * Catches anything {@link #deliverAsync} missed -- the app restarting
     * between a commit and its post-commit async task actually running,
     * or Telegram/Sheets being down at the original delivery attempt.
     * A no-op entirely when both channels are disabled, so this never
     * even queries the table on the default zero-config posture.
     */
    @Scheduled(fixedDelayString = "${app.redundant-trail.retry.fixed-delay-ms:300000}")
    public void retryFailedDeliveries() {
        if (!telegramEnabled && !sheetsEnabled) {
            return;
        }
        List<Long> ids = repository.findIdsWithAnyPendingChannel();
        for (Long id : ids) {
            deliverAsync(id);
        }
    }

    private void attemptTelegram(RedundantTrailEntry entry) {
        if (entry.getTelegramStatus() != DeliveryStatus.PENDING) {
            return;
        }
        try {
            telegramSender.send(formatTelegramMessage(entry));
            entry.setTelegramStatus(DeliveryStatus.SENT);
            entry.setTelegramLastError(null);
        } catch (Exception e) {
            recordFailure(entry, e, true);
        }
    }

    private void attemptSheets(RedundantTrailEntry entry) {
        if (entry.getSheetsStatus() != DeliveryStatus.PENDING) {
            return;
        }
        try {
            sheetsAppender.appendRow(formatSheetsRow(entry));
            entry.setSheetsStatus(DeliveryStatus.SENT);
            entry.setSheetsLastError(null);
        } catch (Exception e) {
            recordFailure(entry, e, false);
        }
    }

    private void recordFailure(RedundantTrailEntry entry, Exception e, boolean telegram) {
        String channel = telegram ? "Telegram" : "Sheets";
        int attempts = (telegram ? entry.getTelegramAttempts() : entry.getSheetsAttempts()) + 1;
        String error = truncate(e.getMessage() != null ? e.getMessage() : e.toString(), LAST_ERROR_MAX_CHARS);
        // attempts >= maxAttempts is terminal (FAILED); otherwise stays PENDING
        // so the next post-commit call or scheduled sweep tries again.
        DeliveryStatus status = attempts >= maxAttempts ? DeliveryStatus.FAILED : DeliveryStatus.PENDING;

        if (telegram) {
            entry.setTelegramAttempts(attempts);
            entry.setTelegramStatus(status);
            entry.setTelegramLastError(error);
        } else {
            entry.setSheetsAttempts(attempts);
            entry.setSheetsStatus(status);
            entry.setSheetsLastError(error);
        }

        if (status == DeliveryStatus.FAILED) {
            log.warn("Redundant trail entry {} exhausted {} attempts on {}, giving up: {}",
                    entry.getId(), attempts, channel, error);
        } else {
            log.info("Redundant trail entry {} {} attempt {} failed, will retry: {}",
                    entry.getId(), channel, attempts, error);
        }
    }

    private String formatTelegramMessage(RedundantTrailEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(entry.getAction()).append('\n');
        sb.append(entry.getEntityType()).append(" #").append(entry.getEntityId());
        if (entry.getActorId() != null) {
            sb.append(" | actor ").append(entry.getActorId());
        }
        if (entry.getCreatedAt() != null) {
            sb.append('\n').append(entry.getCreatedAt().format(TIMESTAMP_FORMAT)).append(" UTC");
        }
        if (entry.getAfterState() != null) {
            sb.append("\nafter: ").append(truncate(entry.getAfterState(), SNAPSHOT_PREVIEW_CHARS));
        } else if (entry.getBeforeState() != null) {
            sb.append("\nbefore: ").append(truncate(entry.getBeforeState(), SNAPSHOT_PREVIEW_CHARS));
        }
        return sb.toString();
    }

    private List<String> formatSheetsRow(RedundantTrailEntry entry) {
        List<String> row = new ArrayList<>();
        row.add(entry.getCreatedAt() != null ? entry.getCreatedAt().format(TIMESTAMP_FORMAT) : "");
        row.add(entry.getAction());
        row.add(entry.getEntityType());
        row.add(String.valueOf(entry.getEntityId()));
        row.add(entry.getActorId() != null ? String.valueOf(entry.getActorId()) : "");
        row.add(entry.getBeforeState() != null ? entry.getBeforeState() : "");
        row.add(entry.getAfterState() != null ? entry.getAfterState() : "");
        return row;
    }

    private static String truncate(String s, int maxChars) {
        if (s == null || s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "...";
    }
}
