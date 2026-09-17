package com.rentalshop.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One row per mutation that needs to survive OUTSIDE the primary Aiven
 * MySQL database (schema.sql's redundant_trail_entries table) -- the
 * Layer-3 "Telegram/Sheets redundant trail" the plan describes. This is a
 * DIFFERENT concern from {@link AuditLog}: audit_logs is the queryable
 * in-DB history for the app itself (System Health screen, per-record
 * history), while this table is a delivery QUEUE whose entire purpose is
 * to get a copy of every mutation somewhere that does NOT go down with the
 * Aiven instance -- a Telegram chat and/or a Google Sheet.
 *
 * Two independent delivery channels are tracked per row rather than one
 * combined status, since Telegram and Sheets are meant to be redundant
 * WITH EACH OTHER, not with the row's own existence -- either, both, or
 * (temporarily, until the next retry sweep) neither may have succeeded at
 * any given moment, and RedundantTrailService needs to know which.
 *
 * A row is only ever inserted when at least one channel is enabled (see
 * RedundantTrailService.enqueue) -- on the default zero-config posture
 * (both channels off) this table simply never receives a write, matching
 * PushSyncService's "runs the fan-out logic against a NoOp sender" posture
 * rather than skipping the call entirely.
 */
@Entity
@Table(name = "redundant_trail_entries")
@Getter
@Setter
@NoArgsConstructor
public class RedundantTrailEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /** Same nullability contract as AuditLog.actorId -- null outside an authenticated request. */
    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "before_state", columnDefinition = "json")
    private String beforeState;

    @Column(name = "after_state", columnDefinition = "json")
    private String afterState;

    @Enumerated(EnumType.STRING)
    @Column(name = "telegram_status", nullable = false, length = 20)
    private DeliveryStatus telegramStatus = DeliveryStatus.DISABLED;

    @Column(name = "telegram_attempts", nullable = false)
    private int telegramAttempts = 0;

    @Column(name = "telegram_last_error", length = 500)
    private String telegramLastError;

    @Enumerated(EnumType.STRING)
    @Column(name = "sheets_status", nullable = false, length = 20)
    private DeliveryStatus sheetsStatus = DeliveryStatus.DISABLED;

    @Column(name = "sheets_attempts", nullable = false)
    private int sheetsAttempts = 0;

    @Column(name = "sheets_last_error", length = 500)
    private String sheetsLastError;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum DeliveryStatus {
        /**
         * Never attempted yet, or a previous attempt failed and another
         * retry is still allowed (attempts &lt; app.redundant-trail.retry.max-attempts)
         * -- the retry sweep in RedundantTrailService only ever looks for this state.
         */
        PENDING,
        /** Delivered successfully -- terminal, never retried again. */
        SENT,
        /** Every attempt allowed by app.redundant-trail.retry.max-attempts has been exhausted -- terminal. */
        FAILED,
        /** This channel was off (app.redundant-trail.{telegram,sheets}.enabled=false) when the row was created -- terminal. */
        DISABLED
    }
}
