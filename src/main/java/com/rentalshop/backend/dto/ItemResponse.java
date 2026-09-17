package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.Item;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
public class ItemResponse {
    private Long id;
    private String itemCode;
    private String name;
    private String category;
    private String subCategory;
    private String size;
    private String color;
    private BigDecimal rentalPrice;
    private BigDecimal deposit;
    private String description;
    private Item.ItemStatus status;
    private Long version;

    /**
     * Cheap on every endpoint, including list: either derived from the full
     * {@link #images} list when that's already been loaded (single-item
     * lookups), or resolved via one bulk query for the whole page on the
     * list endpoint (see {@link com.rentalshop.backend.repository.ItemImageRepository#findPrimaryImageKeysForItems}) --
     * never a per-row image query either way.
     */
    private String primaryImageUrl;

    /**
     * Only populated by endpoints that resolve it explicitly (single-item
     * lookups, i.e. Product Details). Left null on list endpoints so an
     * inventory listing of a few hundred items doesn't pay for a full
     * gallery lookup per row it isn't going to render -- primaryImageUrl
     * above is the cheap exception made just for the grid thumbnail.
     */
    private List<ItemImageResponse> images;

    public static ItemResponse from(Item i) {
        return from(i, null, null);
    }

    /** Derives primaryImageUrl from the given gallery -- used by single-item lookups that already loaded it. */
    public static ItemResponse from(Item i, List<ItemImageResponse> images) {
        String primaryImageUrl = images == null ? null : images.stream()
                .filter(ItemImageResponse::isPrimary)
                .map(ItemImageResponse::getImageUrl)
                .findFirst()
                .orElse(null);
        return from(i, images, primaryImageUrl);
    }

    /** Used by the list endpoint: no per-row gallery, but a bulk-resolved primaryImageUrl. */
    public static ItemResponse from(Item i, List<ItemImageResponse> images, String primaryImageUrl) {
        return ItemResponse.builder()
                .id(i.getId())
                .itemCode(i.getItemCode())
                .name(i.getName())
                .category(i.getCategory())
                .subCategory(i.getSubCategory())
                .size(i.getSize())
                .color(i.getColor())
                .rentalPrice(i.getRentalPrice())
                .deposit(i.getDeposit())
                .description(i.getDescription())
                .status(i.getStatus())
                .version(i.getVersion())
                .primaryImageUrl(primaryImageUrl)
                .images(images)
                .build();
    }
}