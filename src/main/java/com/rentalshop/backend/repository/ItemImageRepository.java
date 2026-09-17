package com.rentalshop.backend.repository;

import com.rentalshop.backend.entity.ItemImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Bulk lookup for the Inventory list endpoint: fetches just the storage
     * key of each item's primary image, one query for the whole page rather
     * than one query per row (which is exactly the per-row cost ItemResponse's
     * Javadoc says the list endpoint must avoid). A plain projection --
     * selecting only itemId/imageKey rather than full ItemImage entities --
     * so this stays cheap even for a few hundred items.
     */
    @Query("select i.item.id as itemId, i.imageKey as imageKey " +
            "from ItemImage i where i.item.id in :itemIds and i.primary = true")
    List<PrimaryImageProjection> findPrimaryImageKeysForItems(@Param("itemIds") List<Long> itemIds);

    interface PrimaryImageProjection {
        Long getItemId();
        String getImageKey();
    }
}