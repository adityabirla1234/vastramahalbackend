package com.rentalshop.backend.service;

import com.rentalshop.backend.entity.Owner;
import com.rentalshop.backend.repository.OwnerRepository;
import com.rentalshop.backend.service.push.PushMessageSender;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Fan-out for FCM push sync: whenever AuditLogService records a mutation
 * (booking/item/customer/image/payment), this notifies every OTHER active
 * device with a silent data push so its Android app knows to refresh
 * rather than wait for its next poll. The device that made the change
 * doesn't need to be told about its own write -- its own UI already
 * reflects it.
 *
 * Deliberately downstream of AuditLogService rather than each individual
 * service (BookingService, ItemService, ...) calling it directly: every
 * mutation already funnels through AuditLogService.write(), so hooking
 * there is one choke point instead of N call sites that could drift out of
 * sync with which actions actually warrant a push.
 */
@Service
@RequiredArgsConstructor
public class PushSyncService {

    private static final Logger log = LoggerFactory.getLogger(PushSyncService.class);

    private final OwnerRepository ownerRepository;
    private final DeviceService deviceService;
    private final PushMessageSender pushMessageSender;

    /**
     * Runs off the request thread (see AuditLogService.schedulePushSync,
     * which also waits for the mutation's transaction to actually commit
     * before calling this) -- neither the original API response nor the
     * DB transaction ever waits on Firebase. actorOwnerId is passed in
     * explicitly rather than read from CurrentDevice here, since
     * CurrentDevice is a plain ThreadLocal that does not follow the
     * request thread onto whatever thread @Async dispatches this to.
     */
    @Async
    public void notifyOtherDevices(String action, String entityType, Long entityId, Long actorOwnerId) {
        List<Owner> targets = ownerRepository.findByDeviceStatusAndFcmTokenIsNotNull(Owner.DeviceStatus.ACTIVE)
                .stream()
                .filter(owner -> actorOwnerId == null || !owner.getId().equals(actorOwnerId))
                .toList();

        if (targets.isEmpty()) {
            return;
        }

        Map<String, String> data = Map.of(
                "type", "SYNC",
                "action", action,
                "entityType", entityType,
                "entityId", String.valueOf(entityId)
        );

        for (Owner target : targets) {
            try {
                PushMessageSender.SendResult result = pushMessageSender.send(target.getFcmToken(), data);
                if (result == PushMessageSender.SendResult.INVALID_TOKEN) {
                    deviceService.clearFcmToken(target.getId());
                }
            } catch (Exception e) {
                // A push failure -- for one device or every device -- must
                // never surface anywhere the original mutation would
                // notice. This already runs post-commit and off-thread
                // purely for other devices' convenience; the mutation
                // itself succeeded regardless of what happens here.
                log.warn("Push sync to device {} failed: {}", target.getId(), e.toString());
            }
        }
    }
}
