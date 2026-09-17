package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.AuditLog;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AuditLogResponse {
    private Long id;
    private Long actorId;
    private String action;
    private String entityType;
    private Long entityId;
    /** Raw JSON text, passed straight through -- the Android app renders this as a read-only diff, not a form. */
    private String beforeState;
    private String afterState;
    private LocalDateTime createdAt;

    public static AuditLogResponse from(AuditLog a) {
        return AuditLogResponse.builder()
                .id(a.getId())
                .actorId(a.getActorId())
                .action(a.getAction())
                .entityType(a.getEntityType())
                .entityId(a.getEntityId())
                .beforeState(a.getBeforeState())
                .afterState(a.getAfterState())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
