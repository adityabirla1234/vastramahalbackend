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
     * Only populated by endpoints that resolve it explicitly (single-item
     * lookups, i.e. Product Details). Left null on list endpoints so an
     * inventory listing of a few hundred items doesn't pay for an image
     * lookup per row it isn't going to render.
     */
    private List<ItemImageResponse> images;

    public static ItemResponse from(Item i) {
        return from(i, null);
    }

    public static ItemResponse from(Item i, List<ItemImageResponse> images) {
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
                .images(images)
                .build();
    }
}
