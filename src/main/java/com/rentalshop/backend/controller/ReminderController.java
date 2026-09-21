package com.rentalshop.backend.controller;

import com.rentalshop.backend.dto.ReminderResponse;
import com.rentalshop.backend.service.ReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reminders")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService reminderService;

    /**
     * The three reminder lists (pickups today, returns due / overdue,
     * payment due) as of right now -- see ReminderService for the exact
     * rules. Called by the Android app when a scheduled reminder fires, not
     * on a timer of its own, so every response is built fresh.
     *
     * No @RequireRole: a read, open to both roles like every other booking
     * lookup (a Viewer device can see the same bookings, so it gets the same
     * reminders). DeviceAuthInterceptor still demands a valid device token.
     *
     * Cache-Control: no-store so that nothing between here and the phone (an
     * HTTP cache, a proxy) can ever answer a reminder with an old list.
     */
    @GetMapping
    public ResponseEntity<ReminderResponse> reminders() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(reminderService.currentReminders());
    }
}
