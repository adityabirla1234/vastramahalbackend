package com.rentalshop.backend.service.trail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Active whenever app.redundant-trail.telegram.enabled isn't explicitly
 * true -- the default, so the backend runs with zero Telegram config out
 * of the box (same posture as push/storage). RedundantTrailService never
 * actually calls this in practice: rows are only enqueued in the first
 * place when at least one channel is enabled (see
 * RedundantTrailService.enqueue), so with telegram disabled a row's
 * telegramStatus starts and stays DISABLED and this bean is never invoked
 * for it. It exists purely so Spring always has a TelegramSender bean to
 * inject, regardless of which flags are set.
 */
@Component
@ConditionalOnProperty(name = "app.redundant-trail.telegram.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpTelegramSender implements TelegramSender {

    @Override
    public void send(String message) {
        // Deliberately does nothing -- see class javadoc for why this
        // should never actually be reached in normal operation.
    }
}
