package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.Owner;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RegisterDeviceResponse {
    private Long ownerId;
    private String name;
    private Owner.Role role;

    /**
     * The raw bearer token. Only ever appears in THIS response — the server
     * stores only its hash (Owner.deviceTokenHash) and cannot produce it
     * again. The Android app must persist this immediately (see
     * DeviceSessionManager) and send it back as the X-Device-Token header on
     * every subsequent request.
     */
    private String deviceToken;
}
