package com.rentalshop.backend.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.rentalshop.backend.dto.AuditLogResponse;
import com.rentalshop.backend.entity.Owner;
import com.rentalshop.backend.repository.AuditLogRepository;
import com.rentalshop.backend.security.RequireRole;

import lombok.RequiredArgsConstructor;

/**
 * Read-only. Backs a future System Health (Admin) screen -- either a
 * shop-wide recent-activity feed, or (with entityType+entityId) a single
 * record's full history, e.g. "show me everything that happened to booking
 * BK-20261210-A1B2C3". Admin only -- a Viewer device has no legitimate
 * reason to see the shop's audit trail.
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    @RequireRole(Owner.Role.ADMIN)
    public List<AuditLogResponse> list(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId) {
        List<com.rentalshop.backend.entity.AuditLog> entries =
                (entityType != null && entityId != null)
                        ? auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId)
                        : auditLogRepository.findTop100ByOrderByCreatedAtDesc();
        return entries.stream().map(AuditLogResponse::from).toList();
    }
}
