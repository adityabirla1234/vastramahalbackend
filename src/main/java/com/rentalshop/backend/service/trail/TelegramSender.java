package com.rentalshop.backend.service.trail;

/**
 * Swappable Telegram-delivery boundary, deliberately mirroring
 * {@code PushMessageSender} and {@code ObjectStorageService} -- exactly one
 * real implementation today (the plain Bot API), but RedundantTrailService
 * never needs to know whether it's talking to the real API or a no-op.
 */
public interface TelegramSender {

    /**
     * Sends one message to the configured chat. Implementations throw on
     * any failure (network, non-2xx response, etc.) -- the caller
     * (RedundantTrailService) is responsible for catching, recording the
     * error, and scheduling a retry; this boundary just attempts delivery.
     */
    void send(String message);
}
