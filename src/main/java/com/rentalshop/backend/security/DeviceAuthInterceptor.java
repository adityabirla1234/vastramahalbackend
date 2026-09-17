package com.rentalshop.backend.security;

import com.rentalshop.backend.entity.Owner;
import com.rentalshop.backend.exception.ForbiddenException;
import com.rentalshop.backend.exception.UnauthenticatedException;
import com.rentalshop.backend.service.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Section 12: "Authorization is enforced by the backend via device tokens —
 * never by hiding buttons in the APK alone." This runs on every request
 * matched by WebMvcConfig's "/api/**" registration (registration itself is
 * excluded, since a device obviously has no token yet when calling it):
 *
 *   1. Require a valid, ACTIVE X-Device-Token header -> else 401.
 *   2. If the matched handler method carries @RequireRole, require the
 *      authenticated device's role to match -> else 403.
 *   3. Publish the resolved device onto {@link CurrentDevice} for the
 *      duration of the request, so controllers/services (e.g.
 *      BookingService setting createdBy) don't need the token threaded
 *      through every method signature.
 */
@Component
@RequiredArgsConstructor
public class DeviceAuthInterceptor implements HandlerInterceptor {

    public static final String DEVICE_TOKEN_HEADER = "X-Device-Token";

    private final DeviceService deviceService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true; // not a controller method (e.g. resource handler) -- nothing to guard
        }

        String token = request.getHeader(DEVICE_TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            throw new UnauthenticatedException("Missing " + DEVICE_TOKEN_HEADER + " header.");
        }

        Owner owner = deviceService.findActiveByRawToken(token)
                .orElseThrow(() -> new UnauthenticatedException("Invalid, unknown, or deactivated device token."));

        CurrentDevice.set(new DeviceContext(owner.getId(), owner.getName(), owner.getRole()));

        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole != null && owner.getRole() != requireRole.value()) {
            throw new ForbiddenException(
                    "This action requires " + requireRole.value() + "; this device is " + owner.getRole() + ".");
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // Always clear, even on exception, so a pooled Tomcat thread never
        // carries one request's device identity into the next request it serves.
        CurrentDevice.clear();
    }
}
