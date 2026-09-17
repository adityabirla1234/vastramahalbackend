package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.CreateItemRequest;
import com.rentalshop.backend.dto.ItemResponse;
import com.rentalshop.backend.dto.UpdateItemRequest;
import com.rentalshop.backend.entity.Item;
import com.rentalshop.backend.repository.ItemImageRepository;
import com.rentalshop.backend.repository.ItemRepository;
import com.rentalshop.backend.service.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
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

    @Transactional
    public ItemResponse createItem(CreateItemRequest req) {
        if (itemRepository.existsByItemCode(req.getItemCode())) {
            // Includes soft-deleted items on purpose -- a retired item_code
            // must never be silently reassigned to a different physical item.
            throw new IllegalArgumentException("itemCode already in use: " + req.getItemCode());
        }

        Item item = new Item();
        item.setItemCode(req.getItemCode());
        item.setName(req.getName());
        item.setCategory(req.getCategory());
        item.setSubCategory(req.getSubCategory());
        item.setSize(req.getSize());
        item.setColor(req.getColor());
        item.setRentalPrice(req.getRentalPrice());
        item.setDeposit(req.getDeposit() == null ? java.math.BigDecimal.ZERO : req.getDeposit());
        item.setDescription(req.getDescription());
        item.setStatus(req.getStatus() == null ? Item.ItemStatus.ACTIVE : req.getStatus());

        Item saved = itemRepository.save(item);
        auditLogService.recordItemCreated(saved);
        return ItemResponse.from(saved);
    }

    /** Returns empty if no non-deleted item exists with this id. */
    @Transactional
    public java.util.Optional<ItemResponse> updateItem(Long id, UpdateItemRequest req) {
        return itemRepository.findById(id)
                .filter(i -> !i.isDeleted())
                .map(item -> {
                    // Captured before any mutation below, so the audit row can show
                    // a real before/after diff instead of just the post-write state.
                    java.util.Map<String, Object> before = auditLogService.snapshotItem(item);

                    // Set the client's expected version BEFORE mutating anything else.
                    // If it doesn't match the row Hibernate actually holds, the UPDATE's
                    // WHERE id=? AND version=? matches zero rows and Hibernate raises
                    // OptimisticLockException -- mapped to 409 STALE_WRITE by
                    // GlobalExceptionHandler, same as a booking write conflict.
                    item.setVersion(req.getVersion());

                    item.setName(req.getName());
                    item.setCategory(req.getCategory());
                    item.setSubCategory(req.getSubCategory());
                    item.setSize(req.getSize());
                    item.setColor(req.getColor());
                    item.setRentalPrice(req.getRentalPrice());
                    item.setDeposit(req.getDeposit() == null ? java.math.BigDecimal.ZERO : req.getDeposit());
                    item.setDescription(req.getDescription());
                    item.setStatus(req.getStatus());

                    Item saved = itemRepository.save(item);
                    auditLogService.recordItemUpdated(before, saved);
                    return ItemResponse.from(saved);
                });
    }

    /**
     * Soft delete only (is_deleted=true) -- item_images and any historical
     * bookings referencing this item must remain intact for records/history,
     * so a hard DELETE is never used here.
     */
    @Transactional
    public boolean deleteItem(Long id) {
        return itemRepository.findById(id)
                .filter(i -> !i.isDeleted())
                .map(item -> {
                    item.setDeleted(true);
                    itemRepository.save(item);
                    auditLogService.recordItemDeleted(item);
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<ItemResponse> listItems(String category, Item.ItemStatus status, boolean includeDeleted) {
        List<Item> items = itemRepository.search(category, status, includeDeleted);
        if (items.isEmpty()) {
            return List.of();
        }

        // One bulk query for the whole page's primary-image keys, resolved to
        // public URLs, rather than a per-row lookup -- keeps this endpoint's
        // "no per-row image cost" guarantee (see ItemResponse's Javadoc) while
        // still giving the Inventory grid a thumbnail to show.
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