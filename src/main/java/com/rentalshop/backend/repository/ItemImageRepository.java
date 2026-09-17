package com.rentalshop.backend.repository;

import com.rentalshop.backend.entity.ItemImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemImageRepository extends JpaRepository<ItemImage, Long> {

    List<ItemImage> findByItemIdOrderByDisplayOrderAsc(Long itemId);

    Optional<ItemImage> findByIdAndItemId(Long id, Long itemId);

    long countByItemId(Long itemId);

    /**
     * Used when the primary image is deleted (or on upload with
     * {@code primary=true}) to demote whichever image currently holds the
     * flag, since exactly zero-or-one image per item should ever be primary.
     */
    Optional<ItemImage> findByItemIdAndPrimaryTrue(Long itemId);
}
