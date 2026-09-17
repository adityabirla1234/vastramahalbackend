package com.rentalshop.backend.service.trail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Real Telegram delivery via the plain Bot API (no SDK dependency --
 * same "one HttpClient, two endpoints" philosophy as
 * ImageKitObjectStorageService: sendMessage is a single simple call, not
 * worth pulling in a whole Telegram bot library for).
 *
 * Activate with:
 *   app.redundant-trail.telegram.enabled=true
 *   app.redundant-trail.telegram.bot-token=123456:ABC-...   (from @BotFather)
 *   app.redundant-trail.telegram.chat-id=-100123456789       (the group/channel to post into;
 *                                                              add the bot to it first)
 *
 * Design notes:
 * <ul>
 *   <li>Not yet exercised against the real Telegram API in this sandbox (no
 *       network access) -- verify bot-token/chat-id against a real chat
 *       before relying on this in production, same caveat as
 *       FirebaseCloudMessagingSender's firebase-admin version.</li>
 *   <li>disable_notification is NOT set -- every mutation buzzing every
 *       admin's phone is arguably the point of a redundant trail (a silent
 *       backup log nobody ever checks defeats the purpose), but this is an
 *       easy follow-up toggle if it turns out too noisy in practice.</li>
 *   <li>parse_mode is deliberately omitted (plain text) -- RedundantTrailService's
 *       message formatting doesn't rely on Markdown/HTML, and skipping it
 *       avoids a delivery failure if a booking note or customer name ever
 *       contains a character Telegram's Markdown parser chokes on.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.redundant-trail.telegram.enabled", havingValue = "true")
public class TelegramBotApiSender implements TelegramSender {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String sendMessageUrl;
    private final String chatId;

    public TelegramBotApiSender(
            @Value("${app.redundant-trail.telegram.bot-token}") String botToken,
            @Value("${app.redundant-trail.telegram.chat-id}") String chatId) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                new ObjectMapper(), botToken, chatId);
    }

    /** Package-private, used by tests to inject a mock HttpClient. */
    TelegramBotApiSender(HttpClient httpClient, ObjectMapper objectMapper, String botToken, String chatId) {
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalStateException(
                    "app.redundant-trail.telegram.bot-token is required when "
                            + "app.redundant-trail.telegram.enabled=true (create a bot via @BotFather).");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalStateException(
                    "app.redundant-trail.telegram.chat-id is required when "
                            + "app.redundant-trail.telegram.enabled=true (add the bot to the target chat, "
                            + "then look up its chat id -- e.g. via the bot's getUpdates response).");
        }
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.sendMessageUrl = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        this.chatId = chatId;
    }

    @Override
    public void send(String message) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "chat_id", chatId,
                    "text", message
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sendMessageUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "Telegram sendMessage failed: HTTP " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Telegram sendMessage request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Telegram sendMessage request interrupted", e);
        }
    }
}
