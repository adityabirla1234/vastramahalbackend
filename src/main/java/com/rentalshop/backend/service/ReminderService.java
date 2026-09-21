package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.ReminderResponse;
import com.rentalshop.backend.entity.Booking;
import com.rentalshop.backend.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Backs GET /api/reminders -- the three lists behind the app's scheduled
 * "pickups today", "returns due" and "payment due" notifications.
 *
 * Everything is computed live from the bookings table on every call, never
 * cached: the app calls this at the moment a notification is due, and the
 * whole point is that a bill picked up, returned or settled five minutes ago
 * is already gone from the list.
 */
@Service
@RequiredArgsConstructor
public class ReminderService {

    /** Not yet out of the shop. New bookings are CONFIRMED; PENDING is the state machine's other pre-pickup state. */
    private static final List<Booking.BookingStatus> AWAITING_PICKUP =
            List.of(Booking.BookingStatus.PENDING, Booking.BookingStatus.CONFIRMED);

    private final BookingRepository bookingRepository;

    /**
     * The shop's own time zone. "Today" has to be decided here, not by the
     * JVM default: a cloud host runs on UTC, so between midnight and 05:30
     * IST the server's date would still be yesterday's. Non-final field
     * injection for the same reason as WebMvcConfig.localStorageDir -- Lombok
     * doesn't copy @Value onto the generated constructor.
     */
    @Value("${app.reminders.zone:Asia/Kolkata}")
    private String zone;

    @Transactional(readOnly = true)
    public ReminderResponse currentReminders() {
        return remindersFor(LocalDate.now(ZoneId.of(zone)));
    }

    /** Split out from {@link #currentReminders()} so "today" can be pinned. */
    @Transactional(readOnly = true)
    public ReminderResponse remindersFor(LocalDate today) {
        List<ReminderBuilder.Row> pickups = bookingRepository.findPickupsOn(AWAITING_PICKUP, today)
                .stream().map(ReminderService::toRow).toList();

        // Only rows that are physically out: an item that was never picked
        // up can't be "returned", so a stale unpicked booking whose return
        // date has passed is not a return reminder.
        List<ReminderBuilder.Row> returns = bookingRepository
                .findOutOnOrPastReturnDate(Booking.BookingStatus.PICKED_UP, today)
                .stream().map(ReminderService::toRow).toList();

        // Strictly PAST the return date (< today), matching "past their
        // return date". Same RETURNED + DUE rule as the Amount Due Bills
        // screen, so settling a bill there drops it from the next reminder.
        List<ReminderBuilder.Row> payments = bookingRepository
                .findUnsettledPastReturnDate(
                        Booking.BookingStatus.RETURNED, Booking.SettlementStatus.DUE, today)
                .stream().map(ReminderService::toRow).toList();

        return new ReminderResponse(
                today,
                ReminderBuilder.pickups(pickups, today),
                ReminderBuilder.returnsDue(returns, today),
                ReminderBuilder.paymentsDue(payments, today));
    }

    private static ReminderBuilder.Row toRow(Booking b) {
        return new ReminderBuilder.Row(
                b.getId(),
                b.getBookingNumber(),
                b.getBillNumber(),
                b.getGroupId(),
                b.getCustomer().getName(),
                b.getCustomer().getPhone(),
                b.getPickupDate(),
                b.getReturnDate(),
                b.getBalanceAmount());
    }
}
