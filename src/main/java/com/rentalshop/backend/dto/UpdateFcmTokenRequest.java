package com.rentalshop.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Body for PUT /api/devices/fcm-token. Always applies to the CALLING
 * device (resolved from the request's own X-Device-Token via
 * CurrentDevice), never an arbitrary ownerId -- a device registering
 * someone else's push token isn't a thing that should ever happen, so the
 * endpoint doesn't even take an id to get that wrong with.
 */
@Getter
@Setter
public class UpdateFcmTokenRequest {

    /**
     * FCM tokens rotate (app reinstall, data clear, Play Services refresh)
     * -- the Android app is expected to call this endpoint again whenever
     * FirebaseMessagingService.onNewToken fires, not just once at setup.
     */
    @NotBlank
    private String fcmToken;
}
