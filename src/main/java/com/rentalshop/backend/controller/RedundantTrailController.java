package com.rentalshop.backend.controller;

import com.rentalshop.backend.dto.RedundantTrailStatusResponse;
import com.rentalshop.backend.entity.Owner;
import com.rentalshop.backend.entity.RedundantTrailEntry.DeliveryStatus;
import com.rentalshop.backend.repository.RedundantTrailEntryRepository;
import com.rentalshop.backend.security.RequireRole;
import com.rentalshop.backend.service.RedundantTrailService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/**
 * Read-mostly, admin-only -- same posture as AuditLogController. Backs a
 * future System Health (Admin) screen's Layer-3 section: "is the
 * Telegram/Sheets redundant trail keeping up, and if not, kick it".
 */
@RestController
@RequestMapping("/api/redundant-trail")
@RequiredArgsConstructor
public class RedundantTrailController {

    private final RedundantTrailEntryRepository repository;
    private final RedundantTrailService redundantTrailService;

    @Value("${app.redundant-trail.telegram.enabled:false}")
    private boolean telegramEnabled;

    @Value("${app.redundant-trail.sheets.enabled:false}")
    private boolean sheetsEnabled;

    @GetMapping("/status")
    @RequireRole(Owner.Role.ADMIN)
    public RedundantTrailStatusResponse status() {
        return new RedundantTrailStatusResponse(
                telegramEnabled,
                sheetsEnabled,
                repository.countByTelegramStatus(DeliveryStatus.SENT),
                repository.countByTelegramStatus(DeliveryStatus.PENDING),
                repository.countByTelegramStatus(DeliveryStatus.FAILED),
                repository.countBySheetsStatus(DeliveryStatus.SENT),
                repository.countBySheetsStatus(DeliveryStatus.PENDING),
                repository.countBySheetsStatus(DeliveryStatus.FAILED)
        );
    }

    /**
     * Manually kicks the retry sweep instead of waiting for the next
     * scheduled run (app.redundant-trail.retry.fixed-delay-ms) -- useful
     * right after fixing a bad bot token / expired service-account key,
     * so the backlog clears immediately rather than on the next tick.
     * Only retries rows still eligible (PENDING on at least one channel);
     * rows already FAILED-out (attempts exhausted) are untouched here --
     * see RedundantTrailService's javadoc on why FAILED is terminal.
     */
    @PostMapping("/retry")
    @RequireRole(Owner.Role.ADMIN)
    public void retryNow() {
        redundantTrailService.retryFailedDeliveries();
    }
}
