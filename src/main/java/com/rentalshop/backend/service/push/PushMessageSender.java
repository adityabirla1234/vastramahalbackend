package com.rentalshop.backend.service.push;

import java.util.Map;

/**
 * Swappable push-delivery boundary, deliberately mirroring how
 * {@code ObjectStorageService} lets the storage provider vary
 * (local/s3/imagekit) without PushSyncService knowing which one is active.
 * Today there's exactly one real implementation (FCM), but keeping the
 * interface thin means a future provider swap -- or a test double -- never
 * has to touch PushSyncService itself.
 */
public interface PushMessageSender {

    /**
     * Sends a single data-only push to one device. {@code data} is a flat
     * string map (FCM's own constraint on data-message payloads) -- the
     * caller decides what goes in it, this boundary just delivers it.
     */
    SendResult send(String fcmToken, Map<String, String> data);

    enum SendResult {
        /** Delivered to the provider for this token. */
        SENT,
        /** The provider reports this token as unregistered/invalid -- caller should stop using it. */
        INVALID_TOKEN,
        /** Send failed for any other reason (network, provider outage, etc.) -- token may still be valid. */
        FAILED
    }
}
