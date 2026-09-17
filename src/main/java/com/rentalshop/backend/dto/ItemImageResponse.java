package com.rentalshop.backend.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ItemImageResponse {
    private Long id;
    private Long itemId;
    /** Fully-resolved, publicly fetchable URL -- resolved from the stored key at read time. */
    private String imageUrl;
    private int displayOrder;
    private boolean primary;
}
