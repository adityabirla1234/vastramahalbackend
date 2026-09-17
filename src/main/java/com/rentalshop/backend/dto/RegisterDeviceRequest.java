package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.Owner;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterDeviceRequest {

    @NotBlank
    private String name;

    @NotNull
    private Owner.Role role;

    /**
     * Shared secret the owners hand each other out-of-band (WhatsApp, in
     * person, etc.) when setting up a new device — see application.yml's
     * app.device.setup-key. This is deliberately NOT a per-owner password:
     * with only 3 devices ever expected, the goal is just to stop a random
     * stranger who finds the backend URL from self-registering as a device,
     * not to build a full user-account system.
     */
    @NotBlank
    private String setupKey;
}
