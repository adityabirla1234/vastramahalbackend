package com.rentalshop.backend.service.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * Real push delivery via Firebase Cloud Messaging. Only instantiated when
 * app.push.provider=fcm (PUSH_PROVIDER env var) -- Spring never constructs
 * this bean, and firebase-admin never touches the network, on a deployment
 * that leaves push disabled.
 *
 * Credentials are a base64-encoded Firebase service-account JSON blob
 * (Project Settings -> Service Accounts -> Generate new private key, then
 * base64 the downloaded file) rather than a file path, matching this app's
 * existing preference (see application.yml's storage/datasource comments)
 * for single-line env vars over files on free-tier hosts that don't make
 * mounting a secret file easy.
 */
@Component
@ConditionalOnProperty(name = "app.push.provider", havingValue = "fcm")
public class FirebaseCloudMessagingSender implements PushMessageSender {

    private static final Logger log = LoggerFactory.getLogger(FirebaseCloudMessagingSender.class);

    private final FirebaseMessaging firebaseMessaging;

    public FirebaseCloudMessagingSender(@Value("${app.fcm.credentials-base64:}") String credentialsBase64) throws IOException {
        if (credentialsBase64 == null || credentialsBase64.isBlank()) {
            // Fails fast at startup rather than lazily on the first push --
            // app.push.provider=fcm with no credentials is a deployment
            // mistake, not a runtime condition to degrade gracefully from.
            throw new IllegalStateException(
                    "app.push.provider=fcm but app.fcm.credentials-base64 (FCM_CREDENTIALS_BASE64) is not set.");
        }

        byte[] decoded = Base64.getDecoder().decode(credentialsBase64);
        GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(decoded));
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .build();

        // Guards against re-initializing on a hot reload / second bean
        // creation attempt -- FirebaseApp.initializeApp throws if called
        // twice for the same (default) app name.
        FirebaseApp app = FirebaseApp.getApps().isEmpty()
                ? FirebaseApp.initializeApp(options)
                : FirebaseApp.getInstance();

        this.firebaseMessaging = FirebaseMessaging.getInstance(app);
    }

    @Override
    public SendResult send(String fcmToken, Map<String, String> data) {
        Message message = Message.builder()
                .setToken(fcmToken)
                .putAllData(data)
                // Data-only message -- no .setNotification(...). This is a
                // silent "something changed, go refresh" signal for the
                // Android app's own sync/pending-action logic to act on,
                // not a user-facing notification banner.
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .build())
                .build();

        try {
            firebaseMessaging.send(message);
            return SendResult.SENT;
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                log.info("FCM token rejected ({}), will be cleared: {}", code, e.getMessage());
                return SendResult.INVALID_TOKEN;
            }
            log.warn("FCM send failed ({}): {}", code, e.getMessage());
            return SendResult.FAILED;
        }
    }
}
