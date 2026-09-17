package com.rentalshop.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One row per uploaded photo for an {@link Item}. Mirrors the item_images
 * table exactly (see schema.sql).
 *
 * [imageKey] intentionally stores the STORAGE KEY (e.g.
 * "items/DRESS-001/3f2a1c.webp"), never a fully-qualified URL, even though
 * the column is named image_url in the DB. Resolving a key to a public URL
 * is done at read time via {@link com.rentalshop.backend.service.storage.ObjectStorageService#publicUrl(String)},
 * so switching storage providers or base domains later never requires a
 * data migration -- only a config change.
 */
@Entity
@Table(name = "item_images")
@Getter
@Setter
@NoArgsConstructor
public class ItemImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageKey;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
