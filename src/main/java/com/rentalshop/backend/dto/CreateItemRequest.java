package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.Item;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
     * the shop floor. Uniqueness against every current item is enforced in
     * {@code ItemService}; deleting an item is a hard delete, so its code
     * becomes free to reuse afterwards.
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


    private String description;

    private Item.ItemStatus status = Item.ItemStatus.ACTIVE;

    /**
     * Client-generated key, stable across retries of the SAME create (the app
     * queues creates while offline and replays them). A repeat with a key that
     * already exists returns the record the first attempt made instead of
     * making a second one. Optional: older app builds don't send it.
     */
    @Size(max = 80)
    private String idempotencyKey;
}
