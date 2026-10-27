package com.rentalshop.backend.controller;

import com.rentalshop.backend.dto.ItemResponse;
import com.rentalshop.backend.service.AvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api/availability")
@RequiredArgsConstructor
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    /**
     * GET /api/availability?start=2026-12-10&startTime=10:00&end=2026-12-15&endTime=13:30&category=Lehenga
     * Matches Section 3.6. category is optional. startTime / endTime ("HH:mm") are
     * optional too: omitted, the search covers the whole start and end days.
     * An item due back at 13:30 on the end date of someone else's booking is
     * available again from 13:31.
     */
    @GetMapping
    public List<ItemResponse> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime,
            @RequestParam(required = false) String category) {
        return availabilityService.searchAvailable(start, startTime, end, endTime, category);
    }
}
