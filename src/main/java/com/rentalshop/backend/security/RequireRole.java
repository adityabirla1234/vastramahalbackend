package com.rentalshop.backend.security;

import com.rentalshop.backend.entity.Owner;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Section 12: "Backend-enforced Admin/Viewer permissions via device tokens —
 * never enforced client-side only." Apply this to any controller method that
 * mutates shared data (bookings, items, customers, payments, ...).
 *
 * Enforced by {@link DeviceAuthInterceptor}, which rejects the request with
 * 403 before the controller method body ever runs if the authenticated
 * device's role doesn't match. Endpoints with no annotation are reachable by
 * any authenticated device (ADMIN or VIEWER) — i.e. read endpoints, which
 * per Section 2 both roles can access.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireRole {
    Owner.Role value();
}
