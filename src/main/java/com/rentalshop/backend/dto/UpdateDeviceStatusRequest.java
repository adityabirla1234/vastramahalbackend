package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.Owner;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateDeviceStatusRequest {

    /** ACTIVE or INACTIVE. Set to INACTIVE to revoke a lost/compromised device's token immediately. */
    @NotNull
    private Owner.DeviceStatus status;
}
