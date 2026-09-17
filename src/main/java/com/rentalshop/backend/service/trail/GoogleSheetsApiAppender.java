package com.rentalshop.backend.service.trail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;

import tools.jackson.databind.ObjectMapper;

/**
 * Real Sheets delivery via the plain Sheets REST API (values.append), not
 * the google-api-services-sheets SDK -- pulling that whole client library
 * in for one HTTP call isn't worth it, and {@code GoogleCredentials} (which
 * this needs anyway for the OAuth2 access token) is already on the
 * classpath transitively via the firebase-admin dependency
 * (FirebaseCloudMessagingSender uses the same class), so this needs zero
 * NEW dependencies.
 *
 * Activate with:
 *   app.redundant-trail.sheets.enabled=true
 *   app.redundant-trail.sheets.spreadsheet-id=1AbC...        (from the sheet's URL)
 *   app.redundant-trail.sheets.sheet-name=AuditTrail          (tab name; created ahead of time -- this
 *                                                               appender does not create sheets/tabs)
 *   app.redundant-trail.sheets.credentials-base64=...         (base64 of a service-account JSON key;
 *                                                               share the spreadsheet with that
 *                                                               service account's email as an Editor)
 *
 * Design notes:
 * <ul>
 *   <li>The access token is cached and only refreshed when expired (or
 *       about to expire) -- values.append is potentially called on every
 *       single mutation, and re-authenticating with Google on every call
 *       would be wasteful and slow. Refresh is synchronized since delivery
 *       can run concurrently across multiple @Async invocations.</li>
 *   <li>Not yet exercised against the real Sheets API in this sandbox (no
 *       network access) -- verify against a real spreadsheet + service
 *       account before relying on this in production. GoogleCredentials's
 *       refreshIfExpired()/createScoped() API surface is stable across
 *       recent google-auth-library-oauth2-http versions, but the exact
 *       version pulled in transitively by firebase-admin 9.4.1 hasn't
 *       been confirmed to compile against this code in this sandbox
 *       either (same caveat as firebase-admin itself in pom.xml).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.redundant-trail.sheets.enabled", havingValue = "true")
public class GoogleSheetsApiAppender implements SheetsAppender {

    private static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GoogleCredentials credentials;
    private final String appendUrl;
    private final Object tokenLock = new Object();

    public GoogleSheetsApiAppender(
            @Value("${app.redundant-trail.sheets.credentials-base64}") String credentialsBase64,
            @Value("${app.redundant-trail.sheets.spreadsheet-id}") String spreadsheetId,
            @Value("${app.redundant-trail.sheets.sheet-name:AuditTrail}") String sheetName) throws IOException {
        if (credentialsBase64 == null || credentialsBase64.isBlank()) {
            throw new IllegalStateException(
                    "app.redundant-trail.sheets.credentials-base64 is required when "
                            + "app.redundant-trail.sheets.enabled=true (base64 of a service-account JSON key).");
        }
        if (spreadsheetId == null || spreadsheetId.isBlank()) {
            throw new IllegalStateException(
                    "app.redundant-trail.sheets.spreadsheet-id is required when "
                            + "app.redundant-trail.sheets.enabled=true (the id segment of the sheet's URL).");
        }

        byte[] decoded = Base64.getDecoder().decode(credentialsBase64);
        this.credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(decoded))
                .createScoped(List.of(SCOPE));

        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.objectMapper = new ObjectMapper();
        // range=<sheetName> (no cell reference) appends after the sheet's last row --
        // Sheets' own values.append semantics, no need to track a row cursor ourselves.
        String range = URLEncoder.encode(sheetName, StandardCharsets.UTF_8);
        this.appendUrl = "https://sheets.googleapis.com/v4/spreadsheets/" + spreadsheetId
                + "/values/" + range + ":append?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS";
    }

    @Override
    public void appendRow(List<String> row) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("values", List.of(row)));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(appendUrl))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "Sheets values.append failed: HTTP " + response.statusCode() + " - " + response.body());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Sheets values.append request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sheets values.append request interrupted", e);
        }
    }

    /** Refreshes the cached OAuth2 access token only when missing/expired -- see class javadoc. */
    private String accessToken() throws IOException {
        synchronized (tokenLock) {
            credentials.refreshIfExpired();
            AccessToken token = credentials.getAccessToken();
            if (token == null) {
                // First call ever, or refreshIfExpired somehow left it null --
                // force an explicit refresh rather than sending a null bearer token.
                credentials.refresh();
                token = credentials.getAccessToken();
            }
            return token.getTokenValue();
        }
    }
}
