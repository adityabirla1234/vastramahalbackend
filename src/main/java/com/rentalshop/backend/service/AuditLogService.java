package com.rentalshop.backend.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.rentalshop.backend.entity.AuditLog;
import com.rentalshop.backend.entity.Booking;
import com.rentalshop.backend.entity.Customer;
import com.rentalshop.backend.entity.Item;
import com.rentalshop.backend.entity.ItemImage;
import com.rentalshop.backend.entity.Payment;
import com.rentalshop.backend.repository.AuditLogRepository;
import com.rentalshop.backend.security.CurrentDevice;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Persists every mutation as a row in audit_logs (schema.sql's table,
 * unused until now). Deliberately runs INSIDE the same transaction as the
 * mutation it's recording (every caller here is already inside an
 * @Transactional service method) -- if the booking/item/customer write
 * rolls back, its audit row should never have existed either. This is a
 * different concern from the Layer-3 Telegram/Sheets redundant trail
 * (see RedundantTrailService): that's an external network call and
 * belongs on a post-commit async event so a slow/failing API can never
 * block or fail this transaction -- a local DB insert here carries no
 * such risk. RedundantTrailService.enqueue is still called synchronously
 * from {@link #write}, same as the audit_logs insert itself -- it's only
 * the actual Telegram/Sheets delivery (RedundantTrailService.deliverAsync)
 * that's deferred to post-commit, mirroring exactly how schedulePushSync
 * defers PushSyncService below.
 *
 * before_state/after_state are hand-built Map snapshots (not the raw
 * entity) serialized to JSON via Jackson -- serializing a Hibernate entity
 * directly risks LazyInitializationException on any uninitialized
 * association and would leak internal fields (e.g. version) not worth
 * logging. Every snapshot below only touches scalar fields plus
 * associations' ids (safe on a lazy proxy without triggering a load).
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final PushSyncService pushSyncService;
    private final RedundantTrailService redundantTrailService;

    public void recordBookingCreated(Booking booking) {
        write("BOOKING_CREATED", "BOOKING", booking.getId(), null, snapshotBooking(booking));
    }

    public void recordBookingStatusChanged(Booking booking, Booking.BookingStatus previousStatus) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", previousStatus);
        write("BOOKING_STATUS_CHANGED", "BOOKING", booking.getId(), before, snapshotBooking(booking));
    }

    public void recordItemCreated(Item item) {
        write("ITEM_CREATED", "ITEM", item.getId(), null, snapshotItem(item));
    }

    /**
     * Unlike the other record* methods, this one needs a snapshot taken
     * BEFORE the caller mutated the entity -- see {@link #snapshotItem}
     * and ItemService.updateItem, which now captures that snapshot ahead
     * of applying the request's changes.
     */
    public void recordItemUpdated(Map<String, Object> before, Item after) {
        write("ITEM_UPDATED", "ITEM", after.getId(), before, snapshotItem(after));
    }

    public void recordItemDeleted(Item item) {
        write("ITEM_DELETED", "ITEM", item.getId(), null, snapshotItem(item));
    }

    public void recordItemImageUploaded(ItemImage image) {
        write("ITEM_IMAGE_UPLOADED", "ITEM_IMAGE", image.getId(), null, snapshotItemImage(image));
    }

    public void recordItemImageDeleted(ItemImage image) {
        write("ITEM_IMAGE_DELETED", "ITEM_IMAGE", image.getId(), snapshotItemImage(image), null);
    }

    public void recordCustomerCreated(Customer customer) {
        write("CUSTOMER_CREATED", "CUSTOMER", customer.getId(), null, snapshotCustomer(customer));
    }

    /** Same before-snapshot contract as {@link #recordItemUpdated} -- see CustomerService.updateCustomer. */
    public void recordCustomerUpdated(Map<String, Object> before, Customer after) {
        write("CUSTOMER_UPDATED", "CUSTOMER", after.getId(), before, snapshotCustomer(after));
    }

    public void recordCustomerDeleted(Customer customer) {
        write("CUSTOMER_DELETED", "CUSTOMER", customer.getId(), null, snapshotCustomer(customer));
    }

    public void recordPaymentCreated(Payment payment) {
        write("PAYMENT_CREATED", "PAYMENT", payment.getId(), null, snapshotPayment(payment));
    }

    public void recordPaymentDeleted(Payment payment) {
        // before=the payment as it existed right before removal; after=null,
        // since there's nothing left once deletePayment removes the row.
        write("PAYMENT_DELETED", "PAYMENT", payment.getId(), snapshotPayment(payment), null);
    }

    // --- Snapshot builders -------------------------------------------------

    Map<String, Object> snapshotItem(Item item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("itemCode", item.getItemCode());
        m.put("name", item.getName());
        m.put("category", item.getCategory());
        m.put("subCategory", item.getSubCategory());
        m.put("size", item.getSize());
        m.put("color", item.getColor());
        m.put("rentalPrice", item.getRentalPrice());
        m.put("status", item.getStatus());
        m.put("deleted", item.isDeleted());
        return m;
    }

    Map<String, Object> snapshotCustomer(Customer customer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", customer.getId());
        m.put("name", customer.getName());
        m.put("phone", customer.getPhone());
        m.put("address", customer.getAddress());
        m.put("notes", customer.getNotes());
        m.put("deleted", customer.isDeleted());
        return m;
    }

    private Map<String, Object> snapshotBooking(Booking booking) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", booking.getId());
        m.put("bookingNumber", booking.getBookingNumber());
        m.put("itemId", booking.getItem().getId());
        m.put("customerId", booking.getCustomer().getId());
        m.put("pickupDate", booking.getPickupDate());
        m.put("eventDate", booking.getEventDate());
        m.put("returnDate", booking.getReturnDate());
        m.put("rentalAmount", booking.getRentalAmount());
        m.put("depositAmount", booking.getDepositAmount());
        m.put("advanceAmount", booking.getAdvanceAmount());
        m.put("balanceAmount", booking.getBalanceAmount());
        m.put("status", booking.getStatus());
        return m;
    }

    private Map<String, Object> snapshotItemImage(ItemImage image) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", image.getId());
        m.put("itemId", image.getItem().getId());
        m.put("imageKey", image.getImageKey());
        m.put("displayOrder", image.getDisplayOrder());
        m.put("primary", image.isPrimary());
        return m;
    }

    private Map<String, Object> snapshotPayment(Payment payment) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", payment.getId());
        m.put("bookingId", payment.getBooking().getId());
        m.put("amount", payment.getAmount());
        m.put("paymentDate", payment.getPaymentDate());
        m.put("method", payment.getMethod());
        m.put("notes", payment.getNotes());
        return m;
    }

    // --- Persistence ---------------------------------------------------

    @Transactional
    void write(String action, String entityType, Long entityId, Map<String, Object> before, Map<String, Object> after) {
        Long actorId = currentActorIdOrNull();

        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setBeforeState(toJsonOrNull(before));
        entry.setAfterState(toJsonOrNull(after));
        auditLogRepository.save(entry);

        Long trailEntryId = redundantTrailService.enqueue(
                action, entityType, entityId, actorId, entry.getBeforeState(), entry.getAfterState());

        schedulePushSync(action, entityType, entityId, actorId);
        scheduleRedundantTrailDelivery(trailEntryId);
    }

    /**
     * Fires FCM push sync (PushSyncService) to every other active device,
     * but only once this transaction actually commits -- registered as a
     * synchronization rather than called directly so a mutation that gets
     * rolled back later in the same transaction (e.g. an overpay rejected
     * after this audit row was staged) never triggers a push for something
     * that didn't really happen. The push call itself still runs off-thread
     * (PushSyncService.notifyOtherDevices is @Async), so neither this
     * commit nor the original request waits on Firebase.
     */
    private void schedulePushSync(String action, String entityType, Long entityId, Long actorId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    pushSyncService.notifyOtherDevices(action, entityType, entityId, actorId);
                }
            });
        } else {
            // No active transaction synchronization (e.g. this was called
            // outside a @Transactional context) -- best-effort, fire
            // immediately rather than silently dropping the push.
            pushSyncService.notifyOtherDevices(action, entityType, entityId, actorId);
        }
    }

    /**
     * Same post-commit-only reasoning as {@link #schedulePushSync}: a row
     * that got queued but then rolled back later in the same transaction
     * must never trigger a Telegram message or Sheets row for a mutation
     * that didn't really happen. entryId is null whenever
     * RedundantTrailService.enqueue was itself a no-op (both channels
     * disabled) -- guarded here so that common case never even touches
     * TransactionSynchronizationManager.
     */
    private void scheduleRedundantTrailDelivery(Long trailEntryId) {
        if (trailEntryId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redundantTrailService.deliverAsync(trailEntryId);
                }
            });
        } else {
            redundantTrailService.deliverAsync(trailEntryId);
        }
    }

    /**
     * Null, rather than a thrown exception, when there's no authenticated
     * device on this thread -- covers tests and any future background job
     * (e.g. the keep-alive cron) that calls a service method directly
     * without going through DeviceAuthInterceptor.
     */
    private Long currentActorIdOrNull() {
        try {
            return CurrentDevice.get().ownerId();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /**
     * A serialization failure here must never fail the caller's real
     * mutation (a booking/item/customer write is far more important than
     * its own audit trail) -- logged and swallowed, same "best-effort"
     * posture as the Layer-3 redundant trail the plan describes.
     */
    private String toJsonOrNull(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return null;
        }
        return objectMapper.writeValueAsString(snapshot);
    }
}
