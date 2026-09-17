package com.rentalshop.backend.repository;

import com.rentalshop.backend.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** A single entity's full change history, newest first -- e.g. "everything that ever happened to booking #42". */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, Long entityId);

    /** Recent-activity feed for a future System Health (Admin) screen -- shop-wide, not scoped to one entity. */
    List<AuditLog> findTop100ByOrderByCreatedAtDesc();
}
