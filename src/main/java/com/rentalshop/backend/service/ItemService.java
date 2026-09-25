package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.CreateItemRequest;
import com.rentalshop.backend.dto.ItemResponse;
import com.rentalshop.backend.dto.UpdateItemRequest;
import com.rentalshop.backend.entity.AccessoryCategory;
import com.rentalshop.backend.entity.Item;
import com.rentalshop.backend.entity.ItemImage;
import com.rentalshop.backend.repository.BookingAccessoryRepository;
import com.rentalshop.backend.repository.BookingRepository;
import com.rentalshop.backend.repository.ItemImageRepository;
import com.rentalshop.backend.repository.ItemRepository;
import com.rentalshop.backend.service.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Full product CRUD (development plan: "Full product CRUD (create/edit/delete)").
 * Sits alongside the read-only lookups already in ItemController (by-code,
 * by-id), which stay untouched since both Admin and Viewer devices use them.
 */
@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;
    private final ItemImageRepository itemImageRepository;
    private final ObjectStorageService objectStorageService;
    private final AuditLogService auditLogService;
    private final BookingRepository bookingRepository;
    private final BookingAccessoryRepository bookingAccessoryRepository;

    @Transactional
    public ItemResponse createItem(CreateItemRequest req) {
        String idempotencyKey = req.getIdempotencyKey() == null || req.getIdempotencyKey().isBlank()
                ? null : req.getIdempotencyKey().trim();
        if (idempotencyKey != null) {
            // A replay of a create that already succeeded (e.g. the response was
            // lost and the app re-sent it): hand back what the first attempt made.
            var existing = itemRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return ItemResponse.from(existing.get());
            }
        }
        if (itemRepository.existsByItemCode(req.getItemCode())) {
            throw new IllegalArgumentException("itemCode already in use: " + req.getItemCode());
        }

        Item item = new Item();
        item.setItemCode(req.getItemCode());
        item.setIdempotencyKey(idempotencyKey);
        item.setName(req.getName());
        item.setCategory(req.getCategory());
        item.setSubCategory(req.getSubCategory());
        item.setSize(req.getSize());
        item.setColor(req.getColor());
        item.setRentalPrice(req.getRentalPrice());
        item.setDescription(req.getDescription());
        item.setStatus(req.getStatus() == null ? Item.ItemStatus.ACTIVE : req.getStatus());

        Item saved = itemRepository.save(item);
        auditLogService.recordItemCreated(saved);
        return ItemResponse.from(saved);
    }

    /** Returns empty if no item exists with this id. */
    @Transactional
    public java.util.Optional<ItemResponse> updateItem(Long id, UpdateItemRequest req) {
        return itemRepository.findById(id)
                .map(item -> {
                    // Captured before any mutation below, so the audit row can show
                    // a real before/after diff instead of just the post-write state.
                    java.util.Map<String, Object> before = auditLogService.snapshotItem(item);

                    // Set the client's expected version BEFORE mutating anything else.
                    // If it doesn't match the row Hibernate actually holds, the UPDATE's
                    // WHERE id=? AND version=? matches zero rows and Hibernate raises
                    // OptimisticLockException -- mapped to 409 STALE_WRITE by
                    // GlobalExceptionHandler, same as a booking write conflict.
                    // Explicit compare: Hibernate ignores a manually-assigned version on a
                    // managed entity (its UPDATE uses the version it loaded), so
                    // item.setVersion(req.getVersion()) would never raise a stale write.
                    if (req.getVersion() == null || !req.getVersion().equals(item.getVersion())) {
                        throw new ObjectOptimisticLockingFailureException(Item.class, item.getId());
                    }

                    item.setName(req.getName());
                    item.setCategory(req.getCategory());
                    item.setSubCategory(req.getSubCategory());
                    item.setSize(req.getSize());
                    item.setColor(req.getColor());
                    item.setRentalPrice(req.getRentalPrice());
                                item.setDescription(req.getDescription());
                    item.setStatus(req.getStatus());

                    Item saved = itemRepository.save(item);
                    auditLogService.recordItemUpdated(before, saved);
                    return ItemResponse.from(saved);
                });
    }

    /**
     * Hard delete. Blocked (409 INVALID_STATE) when the item is still
     * referenced by a booking or by a past booking's accessory snapshot --
     * both bookings.item_id and booking_accessories.item_id have a plain FK
     * with no ON DELETE clause in schema.sql precisely so historical bills
     * can never end up pointing at a row that no longer exists, so those
     * cases are checked explicitly here to fail with a clear message rather
     * than a raw DB constraint-violation error. An item with no history can
     * be deleted freely; retire it via status instead if it might get
     * history later.
     *
     * Any uploaded photos are removed from object storage first -- their
     * item_images rows are cleaned up as a side effect of the DB's own
     * ON DELETE CASCADE on item_id, but the actual bytes in the storage
     * provider are not the DB's problem to clean up.
     */
    @Transactional
    public boolean deleteItem(Long id) {
        Item item = itemRepository.findById(id).orElse(null);
        if (item == null) {
            return false;
        }

        if (bookingRepository.existsByItemId(id) || bookingAccessoryRepository.existsByItemId(id)) {
            throw new IllegalStateException(
                    "Cannot delete item " + item.getItemCode() +
                    ": it has booking history. Change its status instead of deleting it.");
        }

        List<ItemImage> images = itemImageRepository.findByItemIdOrderByDisplayOrderAsc(id);
        for (ItemImage image : images) {
            objectStorageService.delete(image.getImageKey());
        }

        auditLogService.recordItemDeleted(item);
        itemRepository.delete(item);
        return true;
    }

    @Transactional(readOnly = true)
    public List<ItemResponse> listItems(String category, Item.ItemStatus status) {
        return toListResponses(itemRepository.search(category, status));
    }

    /**
     * Backs the New Booking form's "Add accessory" picker: every bookable
     * item in one accessory bucket, so staff can tick the pant / dupatta /
     * jewellery going out with a dress.
     *
     * Returns the same shape as the inventory list (thumbnail included, no
     * per-row gallery) on purpose -- the picker renders rows that look and
     * behave exactly like the inventory rows staff already recognise, and
     * gets the bulk primary-image resolution for free.
     *
     * Empty is a perfectly normal result: a shop that hasn't entered any
     * dupattas yet gets an empty dupatta bucket, not an error. The app
     * shows "nothing in this category yet" rather than a failure.
     */
    @Transactional(readOnly = true)
    public List<ItemResponse> listAccessoryItems(AccessoryCategory category) {
        return toListResponses(itemRepository.findAccessoryCandidates(category.matchTerms()));
    }

    /**
     * Shared by both list endpoints above. One bulk query for the whole
     * page's primary-image keys, resolved to public URLs, rather than a
     * per-row lookup -- keeps the "no per-row image cost" guarantee (see
     * ItemResponse's Javadoc) while still giving each row a thumbnail.
     */
    private List<ItemResponse> toListResponses(List<Item> items) {
        if (items.isEmpty()) {
            return List.of();
        }

        List<Long> ids = items.stream().map(Item::getId).toList();
        Map<Long, String> primaryImageUrlByItemId = itemImageRepository.findPrimaryImageKeysForItems(ids).stream()
                .collect(Collectors.toMap(
                        ItemImageRepository.PrimaryImageProjection::getItemId,
                        p -> objectStorageService.publicUrl(p.getImageKey())));

        return items.stream()
                .map(item -> ItemResponse.from(item, null, primaryImageUrlByItemId.get(item.getId())))
                .toList();
    }
}