package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.AccessoryCategory;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * One accessory the app attached to an item row on the New Booking form.
 *
 * Only [itemId] is authoritative: the server re-reads the inventory item
 * and snapshots its code/name off the real row, so a stale or tampered
 * client can't write a bill line that never existed in inventory.
 *
 * [category] is the bucket the staff member actually tapped ("Jewellery"),
 * sent along purely as a FALLBACK: it's used only when the item's own
 * category/sub-category text doesn't match any known spelling
 * (AccessoryCategory.match). That keeps a sloppily-categorised inventory
 * row from blocking a booking, while still preferring the inventory's own
 * answer whenever it has one. See BookingService.attachAccessories.
 */
@Getter
@Setter
public class BookingAccessoryRequest {

    @NotNull
    private Long itemId;

    private AccessoryCategory category;
}
