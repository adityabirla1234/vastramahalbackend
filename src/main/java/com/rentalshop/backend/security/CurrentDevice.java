package com.rentalshop.backend.security;

/**
 * Request-scoped access to the authenticated device, set by
 * {@link DeviceAuthInterceptor#preHandle} and always cleared in
 * {@link DeviceAuthInterceptor#afterCompletion} (including on exceptions),
 * so a ThreadLocal never leaks across requests on a pooled Tomcat thread.
 *
 * A plain ThreadLocal is deliberately used here instead of pulling in
 * spring-security's SecurityContextHolder — the auth model in this app is
 * one flat bearer token per device with exactly two roles (Section 2), which
 * doesn't need Spring Security's much larger surface area.
 */
public final class CurrentDevice {

    private static final ThreadLocal<DeviceContext> HOLDER = new ThreadLocal<>();

    private CurrentDevice() {
    }

    static void set(DeviceContext context) {
        HOLDER.set(context);
    }

    static void clear() {
        HOLDER.remove();
    }

    /** Never null for any handler reached past {@link DeviceAuthInterceptor} on a protected path. */
    public static DeviceContext get() {
        DeviceContext ctx = HOLDER.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "CurrentDevice.get() called outside an authenticated request. " +
                    "Either this path is misconfigured as public in WebMvcConfig, or this " +
                    "was called from a background thread that doesn't inherit the ThreadLocal.");
        }
        return ctx;
    }
}
