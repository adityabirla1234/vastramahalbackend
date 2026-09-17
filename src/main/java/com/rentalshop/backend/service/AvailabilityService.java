package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.ItemResponse;
import com.rentalshop.backend.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final ItemRepository itemRepository;

    /**
     * Section 3.6: availability must cover the ENTIRE requested range.
     * category is optional (null = all categories).
     *
     * Read-only transaction: this only ever runs SELECTs, so no lock is
     * taken here — availability search is inherently a point-in-time
     * snapshot and can go slightly stale between this call and the actual
     * booking attempt. That's fine and expected: BookingService re-validates
     * with a real lock at booking time (Section 3.8), so a stale availability
     * result can only ever produce a 409 on booking, never a silent
     * double-booking.
     */
    @Transactional(readOnly = true)
    public List<ItemResponse> searchAvailable(LocalDate start, LocalDate end, String category) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end date cannot be before start date");
        }
        return itemRepository.findAvailableInRange(start, end, category)
                .stream()
                .map(ItemResponse::from)
                .toList();
    }
}
