package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.AccessoryCategory;
import com.rentalshop.backend.entity.BookingAccessory;
import lombok.Builder;
import lombok.Getter;

/**
 * One accessory line on a booking, as the app renders it behind the
 * "Accessories" button on Booking Detail / booking history / the group
 * bill view.
 *
 * Every display field here comes from the snapshot taken at booking time
 * (see BookingAccessory's class doc), so this response never depends on
 * the inventory item still existing, still being named the same, or still
 * being filed under the same category. [itemId] is passed through so the
 * app can still offer to open the live product when it does exist.
 */
@Getter
@Builder
public class BookingAccessoryResponse {

    private Long id;
    private Long itemId;
    private String itemCode;
    private String itemName;
    private AccessoryCategory category;

    /** Ready-to-display bucket name ("Jewellery"), so the app doesn't re-map the enum itself. */
    private String categoryLabel;

    public static BookingAccessoryResponse from(BookingAccessory accessory) {
        return BookingAccessoryResponse.builder()
                .id(accessory.getId())
                .itemId(accessory.getItemId())
                .itemCode(accessory.getItemCode())
                .itemName(accessory.getItemName())
                .category(accessory.getCategory())
                .categoryLabel(accessory.getCategory() == null ? null : accessory.getCategory().label())
                .build();
    }
}
