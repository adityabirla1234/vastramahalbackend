package com.rentalshop.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One row per mutation recorded by {@link com.rentalshop.backend.service.AuditLogService}
 * (schema.sql's audit_logs table, present since the original schema but
 * never given an entity until now).
 *
 * [beforeState] / [afterState] are stored as plain JSON text, not a
 * structured JPA embeddable -- what gets logged differs per entity type
 * (a booking snapshot has different fields than a payment snapshot), and
 * schema.sql already types these columns JSON specifically so an admin can
 * query into them directly with MySQL's JSON_EXTRACT if ever needed,
 * without this app owning a rigid Java shape for every possible snapshot.
 * AuditLogService is responsible for producing valid JSON text (via Jackson)
 * before it ever reaches this entity.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The device/owner that performed the action. Null when recorded outside an authenticated request (e.g. a test or a future cron job). */
    @Column(name = "actor_id")
    private Long actorId;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "before_state", columnDefinition = "json")
    private String beforeState;

    @Column(name = "after_state", columnDefinition = "json")
    private String afterState;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
