package com.rentalshop.backend.service.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Active whenever app.push.provider isn't explicitly "fcm" -- the default,
 * so the backend runs with zero push config out of the box (same posture as
 * storage's "local" default). PushSyncService still runs its full fan-out
 * logic against this; it just never actually reaches a network.
 */
@Component
@ConditionalOnProperty(name = "app.push.provider", havingValue = "none", matchIfMissing = true)
public class NoOpPushMessageSender implements PushMessageSender {

    private static final Logger log = LoggerFactory.getLogger(NoOpPushMessageSender.class);

    private volatile boolean warned = false;

    @Override
    public SendResult send(String fcmToken, Map<String, String> data) {
        if (!warned) {
            // Logged once, not per-send -- this fires on every mutation
            // otherwise once real usage starts.
            log.info("app.push.provider=none: push sync is a no-op. Set app.push.provider=fcm " +
                    "and app.fcm.credentials-base64 (FCM_CREDENTIALS_BASE64) to enable real pushes.");
            warned = true;
        }
        return SendResult.SENT;
    }
}
