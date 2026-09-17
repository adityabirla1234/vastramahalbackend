package com.rentalshop.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Section 2 / 12: one row per registered device (an "owner" here really
 * means "owner's device" — the 3-owner shop registers one device each, but
 * the model doesn't stop a 4th device from being registered later).
 *
 * [deviceTokenHash] is the SHA-256 hash of the bearer token issued at
 * registration time, NEVER the raw token itself. The raw token is returned
 * to the client exactly once, in the registration response, and is not
 * recoverable from the database afterwards — losing it means the device
 * must be re-registered (or an admin issues a fresh token some other way).
 * This mirrors how you'd treat an API key: the DB only ever needs to verify
 * a presented token, never display or reissue the original.
 */
@Entity
@Table(name = "owners")
@Getter
@Setter
@NoArgsConstructor
public class Owner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "device_token", length = 255)
    private String deviceTokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_status", length = 20)
    private DeviceStatus deviceStatus = DeviceStatus.ACTIVE;

    /**
     * Firebase Cloud Messaging registration token for this device, or null
     * if the device hasn't registered one yet (e.g. an app version that
     * predates push sync, or Google Play Services unavailable). Unlike
     * {@link #deviceTokenHash}, this is NOT a secret and is stored in the
     * clear -- it identifies where to deliver a push, not who's allowed to
     * call the API, and Firebase itself only accepts sends from a
     * credentialed backend anyway. See PushSyncService.
     */
    @Column(name = "fcm_token", length = 255)
    private String fcmToken;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum Role {
        ADMIN, VIEWER
    }

    public enum DeviceStatus {
        ACTIVE, INACTIVE
    }
}
