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
public class UpdateItemRequest {

    /**
     * The item's version at the time the editing device last loaded it
     * (from {@code ItemResponse.version}). ItemService applies this to the
     * entity's @Version field before saving, so a genuinely stale edit --
     * e.g. two devices editing the same item while one was offline --
     * fails the UPDATE ... WHERE id=? AND version=? check and surfaces as
     * the existing 409 STALE_WRITE response, exactly like a booking would.
     */
    @NotNull
    private Long version;

    @NotBlank
    private String name;

    private String category;

    private String subCategory;

    private String size;

    private String color;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal rentalPrice;


    private String description;

    @NotNull
    private Item.ItemStatus status;

    // itemCode is intentionally NOT editable here -- it's a physical tag;
    // retagging an item is rare enough to be a delete + re-create in V1
    // rather than a rename that could silently orphan historical bookings.
}
