package com.rentalshop.backend.controller;

import com.rentalshop.backend.dto.ItemResponse;
import com.rentalshop.backend.service.AvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/availability")
@RequiredArgsConstructor
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    /**
     * GET /api/availability?start=2026-12-10&end=2026-12-15&category=Lehenga
     * Matches Section 3.6. category is optional.
     */
    @GetMapping
    public List<ItemResponse> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) String category) {
        return availabilityService.searchAvailable(start, end, category);
    }
}
