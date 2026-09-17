package com.rentalshop.backend.controller;

import com.rentalshop.backend.dto.OwnerResponse;
import com.rentalshop.backend.dto.RegisterDeviceRequest;
import com.rentalshop.backend.dto.RegisterDeviceResponse;
import com.rentalshop.backend.dto.UpdateDeviceStatusRequest;
import com.rentalshop.backend.dto.UpdateFcmTokenRequest;
import com.rentalshop.backend.entity.Owner;
import com.rentalshop.backend.security.CurrentDevice;
import com.rentalshop.backend.security.RequireRole;
import com.rentalshop.backend.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    /**
     * The one endpoint on /api/** that WebMvcConfig leaves unauthenticated
     * (a device has no token yet). Gated instead by the shared setup key
     * (Section 2) -- see DeviceService.register. Returns the raw device
     * token exactly once; the Android app must store it immediately
     * (DeviceSessionManager) and send it as X-Device-Token from then on.
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterDeviceResponse> register(@Valid @RequestBody RegisterDeviceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(deviceService.register(request));
    }

    /** Settings / System Health screen (Section 6): list all registered devices. Admin only. */
    @GetMapping
    @RequireRole(Owner.Role.ADMIN)
    public List<OwnerResponse> list() {
        return deviceService.listDevices();
    }

    /**
     * Revoke (or reactivate) a device -- e.g. a phone was lost. Takes effect
     * immediately: the next request bearing that device's token gets 401,
     * since DeviceAuthInterceptor only accepts ACTIVE devices.
     */
    @PatchMapping("/{ownerId}/status")
    @RequireRole(Owner.Role.ADMIN)
    public OwnerResponse updateStatus(@PathVariable Long ownerId, @Valid @RequestBody UpdateDeviceStatusRequest request) {
        return deviceService.updateStatus(ownerId, request);
    }

    /**
     * Registers/refreshes the calling device's FCM token for push sync
     * (PushSyncService). No @RequireRole -- both Admin and Viewer devices
     * should receive sync pushes, since both read shared data. Always acts
     * on the token's own owner id (CurrentDevice), never a path parameter.
     */
    @PutMapping("/fcm-token")
    public ResponseEntity<Void> updateFcmToken(@Valid @RequestBody UpdateFcmTokenRequest request) {
        deviceService.updateFcmToken(CurrentDevice.get().ownerId(), request.getFcmToken());
        return ResponseEntity.noContent().build();
    }
}
