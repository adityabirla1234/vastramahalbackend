package com.rentalshop.backend.exception;

import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Central place mapping domain exceptions to HTTP responses. This matters
 * more than usual here because the Android app's offline-first UI branches
 * on status code: 409 must always mean "not confirmed, don't optimistically
 * update the local calendar" (Section 5 / 14.9), never a generic 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthenticated(UnauthenticatedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body("UNAUTHENTICATED", ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body("FORBIDDEN", ex.getMessage()));
    }

    @ExceptionHandler(BookingConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(BookingConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("BOOKING_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(Exception ex) {
        // The item/booking was modified by another device between read and write.
        // App should refresh and let the user retry — never silently overwrite.
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("STALE_WRITE", "This record was changed elsewhere. Refresh and try again."));
    }

    /**
     * A constraint fired at commit -- in practice either a unique constraint
     * (two identical creates racing past the idempotency lookup) or, since
     * item/customer delete became a real hard DELETE, a foreign key still
     * pointing at the row (ItemService/CustomerService check for this ahead
     * of time and fail with a clearer 409 INVALID_STATE, but this is the
     * backstop for a race between that check and the delete itself). Either
     * way, answer 409 so the client treats it as "not applied, don't
     * optimistically update" instead of an opaque 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("DATA_CONFLICT",
                        "This couldn't be completed: it either duplicates an existing record or is still referenced by another record."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(body("BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflictState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("INVALID_STATE", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(body("VALIDATION_ERROR", detail));
    }

    private Map<String, Object> body(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("message", message);
        m.put("timestamp", Instant.now().toString());
        return m;
    }
}
