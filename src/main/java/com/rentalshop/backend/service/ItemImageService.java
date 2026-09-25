package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.ItemImageResponse;
import com.rentalshop.backend.entity.Item;
import com.rentalshop.backend.entity.ItemImage;
import com.rentalshop.backend.repository.ItemImageRepository;
import com.rentalshop.backend.repository.ItemRepository;
import com.rentalshop.backend.service.image.ImageProcessingService;
import com.rentalshop.backend.service.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Photo upload + WebP compression + object storage (development plan gap).
 * Orchestrates ImageProcessingService (resize/compress) and
 * ObjectStorageService (where the bytes actually live) around the
 * item_images rows.
 */
@Service
@RequiredArgsConstructor
public class ItemImageService {

    /**
     * Soft cap per item. Not in the development plan, but unbounded uploads
     * on a free-tier storage bucket for a small shop is an easy way to burn
     * through quota by accident (e.g. a device retrying a stuck upload) --
     * cheap to guard against.
     */
    private static final int MAX_IMAGES_PER_ITEM = 12;

    private final ItemRepository itemRepository;
    private final ItemImageRepository itemImageRepository;
    private final ImageProcessingService imageProcessingService;
    private final ObjectStorageService objectStorageService;
    private final AuditLogService auditLogService;

    /**
     * Unlike BookingService, this deliberately does NOT take a pessimistic
     * lock on the item before reading existingCount/isFirstImage below.
     * Two devices uploading a photo to the SAME item at the exact same
     * instant could in theory both compute displayOrder=0 or both end up
     * primary -- cosmetic glitches an admin can fix with one tap via
     * setPrimary, not a correctness or money problem like a double-booking.
     * Given that, the extra lock contention isn't worth it here.
     */
    @Transactional
    public ItemImageResponse uploadImage(Long itemId, MultipartFile file, boolean makePrimary) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image uploads are allowed (got: " + contentType + ")");
        }

        long existingCount = itemImageRepository.countByItemId(itemId);
        if (existingCount >= MAX_IMAGES_PER_ITEM) {
            throw new IllegalStateException(
                    "Item " + item.getItemCode() + " already has the maximum of " + MAX_IMAGES_PER_ITEM + " photos.");
        }

        byte[] rawBytes;
        try {
            rawBytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }

        ImageProcessingService.ProcessedImage processed =
                imageProcessingService.process(rawBytes, file.getOriginalFilename());

        String key = "items/" + item.getItemCode() + "/" + UUID.randomUUID() + "." + processed.fileExtension();
        objectStorageService.upload(key, processed.data(), processed.contentType());

        boolean isFirstImage = existingCount == 0;
        boolean primary = makePrimary || isFirstImage;
        if (primary) {
            demoteCurrentPrimary(itemId);
        }

        ItemImage image = new ItemImage();
        image.setItem(item);
        image.setImageKey(key);
        image.setDisplayOrder((int) existingCount);
        image.setPrimary(primary);

        ItemImage saved = itemImageRepository.save(image);
        auditLogService.recordItemImageUploaded(saved);
        return toResponse(saved, itemId);
    }

    @Transactional(readOnly = true)
    public List<ItemImageResponse> listImages(Long itemId) {
        return itemImageRepository.findByItemIdOrderByDisplayOrderAsc(itemId).stream()
                // itemId passed explicitly rather than via image.getItem().getId() --
                // Item is a LAZY association here, and we already have the id from
                // the request path, so there's no reason to trigger a lazy load per row.
                .map(image -> toResponse(image, itemId))
                .toList();
    }

    @Transactional
    public boolean deleteImage(Long itemId, Long imageId) {
        Optional<ItemImage> found = itemImageRepository.findByIdAndItemId(imageId, itemId);
        if (found.isEmpty()) {
            return false;
        }
        ItemImage image = found.get();
        boolean wasPrimary = image.isPrimary();

        objectStorageService.delete(image.getImageKey());
        itemImageRepository.delete(image);
        auditLogService.recordItemImageDeleted(image);

        if (wasPrimary) {
            // Promote the earliest remaining image so the item is never left
            // with zero primary photos while it still has photos at all.
            itemImageRepository.findByItemIdOrderByDisplayOrderAsc(itemId).stream()
                    .findFirst()
                    .ifPresent(next -> {
                        next.setPrimary(true);
                        itemImageRepository.save(next);
                    });
        }
        return true;
    }

    @Transactional
    public Optional<ItemImageResponse> setPrimary(Long itemId, Long imageId) {
        return itemImageRepository.findByIdAndItemId(imageId, itemId)
                .map(image -> {
                    demoteCurrentPrimary(itemId);
                    image.setPrimary(true);
                    ItemImage saved = itemImageRepository.save(image);
                    return toResponse(saved, itemId);
                });
    }

    private void demoteCurrentPrimary(Long itemId) {
        itemImageRepository.findByItemIdAndPrimaryTrue(itemId).ifPresent(current -> {
            current.setPrimary(false);
            itemImageRepository.save(current);
        });
    }

    private ItemImageResponse toResponse(ItemImage image, Long itemId) {
        return ItemImageResponse.builder()
                .id(image.getId())
                .itemId(itemId)
                .imageUrl(objectStorageService.publicUrl(image.getImageKey()))
                .displayOrder(image.getDisplayOrder())
                .primary(image.isPrimary())
                .build();
    }
}
