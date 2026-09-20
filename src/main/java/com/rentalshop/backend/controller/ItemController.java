package com.rentalshop.backend.controller;

import com.rentalshop.backend.dto.BookingResponse;
import com.rentalshop.backend.dto.CreateItemRequest;
import com.rentalshop.backend.dto.ItemResponse;
import com.rentalshop.backend.dto.UpdateItemRequest;
import com.rentalshop.backend.entity.AccessoryCategory;
import com.rentalshop.backend.entity.Item;
import com.rentalshop.backend.entity.Owner;
import com.rentalshop.backend.repository.ItemRepository;
import com.rentalshop.backend.security.RequireRole;
import com.rentalshop.backend.service.BookingService;
import com.rentalshop.backend.service.ItemImageService;
import com.rentalshop.backend.service.ItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemRepository itemRepository;
    private final ItemService itemService;
    private final ItemImageService itemImageService;
    private final BookingService bookingService;

    /**
     * Section 3.5: item-code search. In production the Android app serves
     * this from its local Room cache for instant results and only calls this
     * endpoint on cache miss or during a full sync — not on every keystroke.
     */
    @GetMapping("/by-code/{itemCode}")
    public ResponseEntity<ItemResponse> findByCode(@PathVariable String itemCode) {
        return itemRepository.findByItemCodeAndDeletedFalse(itemCode)
                .map(this::withImages)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ItemResponse> findById(@PathVariable Long id) {
        return itemRepository.findById(id)
                .filter(i -> !i.isDeleted())
                .map(this::withImages)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Inventory list screen. category/status are optional filters;
     * includeDeleted defaults to false and should only be set true from an
     * explicit "show retired items" admin view. The full photo gallery is
     * intentionally NOT included per row here — only a bulk-resolved
     * primaryImageUrl for the grid thumbnail — see ItemResponse's Javadoc.
     */
    @GetMapping
    public ResponseEntity<List<ItemResponse>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Item.ItemStatus status,
            @RequestParam(defaultValue = "false") boolean includeDeleted) {
        return ResponseEntity.ok(itemService.listItems(category, status, includeDeleted));
    }

    /**
     * New Booking form, "Add accessory" picker: every bookable item in one
     * accessory bucket. GET /api/items/accessories?category=JEWELLERY
     *
     * The three buckets are a fixed vocabulary (AccessoryCategory), not
     * whatever distinct strings happen to be in items.category -- see that
     * enum's Javadoc. An unknown value gets a 400 from Spring's own enum
     * binding before this method runs.
     *
     * Mapped ABOVE nothing in particular but worth noting: this literal
     * path and the /{id} lookup above can't collide -- Spring matches the
     * literal segment first, so /api/items/accessories never gets parsed
     * as an item id.
     *
     * Open read, same posture as every other item lookup here: a Viewer
     * device can see what accessories exist even though it can't create the
     * booking that would attach them.
     */
    @GetMapping("/accessories")
    public ResponseEntity<List<ItemResponse>> accessories(@RequestParam AccessoryCategory category) {
        return ResponseEntity.ok(itemService.listAccessoryItems(category));
    }

    @PostMapping
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<ItemResponse> create(@Valid @RequestBody CreateItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(itemService.createItem(request));
    }

    @PutMapping("/{id}")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<ItemResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateItemRequest request) {
        return itemService.updateItem(id, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Soft delete — see ItemService.deleteItem for why this never hard-deletes. */
    @DeleteMapping("/{id}")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean deleted = itemService.deleteItem(id);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Item-wise Calendar screen. GET /api/items/{id}/calendar?start=2026-12-01&end=2026-12-31
     * returns every booking occupying this item's calendar in that range —
     * see BookingService.getItemCalendar for why it deliberately reuses the
     * same overlap query booking-creation relies on. Open read, same as
     * every other item lookup — both Admin and Viewer devices need this to
     * show "when is this item free" without being able to book it.
     */
    @GetMapping("/{id}/calendar")
    public ResponseEntity<List<BookingResponse>> calendar(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return bookingService.getItemCalendar(id, start, end)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ItemResponse withImages(Item item) {
        return ItemResponse.from(item, itemImageService.listImages(item.getId()));
    }
}