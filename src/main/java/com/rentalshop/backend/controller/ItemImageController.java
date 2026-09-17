package com.rentalshop.backend.controller;

import com.rentalshop.backend.dto.ItemImageResponse;
import com.rentalshop.backend.entity.Owner;
import com.rentalshop.backend.security.RequireRole;
import com.rentalshop.backend.service.ItemImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/items/{itemId}/images")
@RequiredArgsConstructor
public class ItemImageController {

    private final ItemImageService itemImageService;

    /**
     * multipart/form-data upload. The backend does ALL processing
     * (resize + WebP compression) server-side rather than trusting the
     * Android app to pre-compress -- three different devices means three
     * different camera pipelines, and centralizing this in one place is the
     * only way to guarantee every stored photo is actually small.
     *
     * {@code primary=true} explicitly makes this the item's primary photo;
     * the very first photo uploaded for an item always becomes primary
     * regardless of this flag, so an item is never left with photos but no
     * primary.
     */
    @PostMapping(consumes = "multipart/form-data")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<ItemImageResponse> upload(
            @PathVariable Long itemId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "primary", defaultValue = "false") boolean primary) {
        ItemImageResponse response = itemImageService.uploadImage(itemId, file, primary);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ItemImageResponse>> list(@PathVariable Long itemId) {
        return ResponseEntity.ok(itemImageService.listImages(itemId));
    }

    @DeleteMapping("/{imageId}")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<Void> delete(@PathVariable Long itemId, @PathVariable Long imageId) {
        boolean deleted = itemImageService.deleteImage(itemId, imageId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PatchMapping("/{imageId}/primary")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<ItemImageResponse> setPrimary(@PathVariable Long itemId, @PathVariable Long imageId) {
        return itemImageService.setPrimary(itemId, imageId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
