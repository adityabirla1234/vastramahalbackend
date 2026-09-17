package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.Item;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CreateItemRequest {

    /**
     * The physical tag/code written on the item itself -- typed by an admin,
     * not generated server-side, since it needs to match what's already on
     * the shop floor. Uniqueness (including against soft-deleted items, so a
     * retired code is never silently reused for a different physical item)
     * is enforced in {@code ItemService}.
     */
    @NotBlank
    private String itemCode;

    @NotBlank
    private String name;

    private String category;

    private String subCategory;

    private String size;

    private String color;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal rentalPrice;

    @DecimalMin("0.0")
    private BigDecimal deposit = BigDecimal.ZERO;

    private String description;

    private Item.ItemStatus status = Item.ItemStatus.ACTIVE;
}
