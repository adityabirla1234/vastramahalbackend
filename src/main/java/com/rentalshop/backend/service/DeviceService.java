package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.OwnerResponse;
import com.rentalshop.backend.dto.RegisterDeviceRequest;
import com.rentalshop.backend.dto.RegisterDeviceResponse;
import com.rentalshop.backend.dto.UpdateDeviceStatusRequest;
import com.rentalshop.backend.entity.Owner;
import com.rentalshop.backend.repository.OwnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Section 2 / 12: device/role registration and the token verification used
 * by {@link com.rentalshop.backend.security.DeviceAuthInterceptor} on every
 * request. This is the piece the earlier scaffold flagged as "the biggest
 * real gap — nothing stops a Viewer from calling write endpoints"; from this
 * point on that's enforced here and in the interceptor, not just by hiding
 * buttons in the Android app.
 */
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final OwnerRepository ownerRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Shared setup secret (Section 2's "lightweight device/user role
     * registration" — there's no public customer login, so this single
     * shared key, distributed to the 3 owners out-of-band, is the entire
     * gate on who can register a new device). Set via the DEVICE_SETUP_KEY
     * env var in production; the default here is intentionally obviously
     * insecure so a deployment that forgets to override it is easy to spot.
     */
    @Value("${app.device.setup-key:CHANGE_ME_BEFORE_DEPLOY}")
    private String expectedSetupKey;

    @Transactional
    public RegisterDeviceResponse register(RegisterDeviceRequest request) {
        if (!constantTimeEquals(request.getSetupKey(), expectedSetupKey)) {
            // Deliberately the same exception/status as a bad token on any
            // other endpoint (401) rather than a distinct "wrong setup key"
            // error — no need to help an attacker distinguish the two.
            throw new com.rentalshop.backend.exception.UnauthenticatedException("Invalid setup key.");
        }

        String rawToken = generateRawToken();

        Owner owner = new Owner();
        owner.setName(request.getName());
        owner.setRole(request.getRole());
        owner.setDeviceTokenHash(hash(rawToken));
        owner.setDeviceStatus(Owner.DeviceStatus.ACTIVE);
        Owner saved = ownerRepository.save(owner);

        return RegisterDeviceResponse.builder()
                .ownerId(saved.getId())
                .name(saved.getName())
                .role(saved.getRole())
                .deviceToken(rawToken)
                .build();
    }

    /** Used by DeviceAuthInterceptor on every authenticated request. */
    @Transactional(readOnly = true)
    public Optional<Owner> findActiveByRawToken(String rawToken) {
        return ownerRepository.findByDeviceTokenHash(hash(rawToken))
                .filter(o -> o.getDeviceStatus() == Owner.DeviceStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<OwnerResponse> listDevices() {
        return ownerRepository.findAll().stream().map(OwnerResponse::from).toList();
    }

    /** Section 12: lets an Admin revoke a lost/compromised device's access immediately. */
    @Transactional
    public OwnerResponse updateStatus(Long ownerId, UpdateDeviceStatusRequest request) {
        Owner owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Device not found: " + ownerId));
        owner.setDeviceStatus(request.getStatus());
        return OwnerResponse.from(ownerRepository.save(owner));
    }

    /**
     * Called by the Android app right after FCM hands it a token (initial
     * install, and again any time FirebaseMessagingService.onNewToken
     * fires). Always the calling device's own row -- see
     * UpdateFcmTokenRequest's javadoc for why there's no ownerId parameter
     * here beyond "whoever DeviceAuthInterceptor resolved this request to".
     */
    @Transactional
    public void updateFcmToken(Long ownerId, String fcmToken) {
        Owner owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Device not found: " + ownerId));
        owner.setFcmToken(fcmToken);
        ownerRepository.save(owner);
    }

    /**
     * Best-effort cleanup called by PushSyncService when Firebase reports a
     * token as unregistered/invalid (app uninstalled, token rotated and the
     * old one never got overwritten, etc.) -- stops the next push cycle
     * from wasting a call on a token that will only ever fail again.
     */
    @Transactional
    public void clearFcmToken(Long ownerId) {
        ownerRepository.findById(ownerId).ifPresent(owner -> {
            owner.setFcmToken(null);
            ownerRepository.save(owner);
        });
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every JVM; this can't happen.
            throw new IllegalStateException(e);
        }
    }

    /** Avoids leaking setup-key correctness via response-time timing differences. */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
