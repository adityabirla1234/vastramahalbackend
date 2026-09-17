package com.rentalshop.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "items")
@Getter
@Setter
@NoArgsConstructor
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_code", nullable = false, unique = true, length = 40)
    private String itemCode;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 80)
    private String category;

    @Column(name = "sub_category", length = 80)
    private String subCategory;

    @Column(length = 30)
    private String size;

    @Column(length = 40)
    private String color;

    @Column(name = "rental_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal rentalPrice = BigDecimal.ZERO;

    @Column(precision = 10, scale = 2)
    private BigDecimal deposit = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ItemStatus status = ItemStatus.ACTIVE;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    /**
     * Optimistic concurrency guard. Hibernate manages this automatically on
     * every UPDATE; a stale write throws OptimisticLockException, which the
     * controller layer should translate into HTTP 409 so the app can prompt
     * "this item changed elsewhere, refresh and retry".
     */
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum ItemStatus {
        ACTIVE, INACTIVE, MAINTENANCE, LOST, DAMAGED
    }
}
