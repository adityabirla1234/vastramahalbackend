package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.Owner;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/** Never includes the token or its hash — used for the admin device list (Settings screen). */
@Getter
@Builder
public class OwnerResponse {
    private Long id;
    private String name;
    private Owner.Role role;
    private Owner.DeviceStatus deviceStatus;
    private LocalDateTime createdAt;

    public static OwnerResponse from(Owner o) {
        return OwnerResponse.builder()
                .id(o.getId())
                .name(o.getName())
                .role(o.getRole())
                .deviceStatus(o.getDeviceStatus())
                .createdAt(o.getCreatedAt())
                .build();
    }
}
