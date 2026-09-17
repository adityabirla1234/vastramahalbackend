package com.rentalshop.backend.security;

import com.rentalshop.backend.entity.Owner;

/**
 * The resolved identity of whichever device presented a valid X-Device-Token
 * on this request. Populated by {@link DeviceAuthInterceptor} and read by
 * controllers/services via {@link CurrentDevice} — never constructed
 * directly outside the interceptor.
 */
public record DeviceContext(Long ownerId, String name, Owner.Role role) {

    public boolean isAdmin() {
        return role == Owner.Role.ADMIN;
    }
}
