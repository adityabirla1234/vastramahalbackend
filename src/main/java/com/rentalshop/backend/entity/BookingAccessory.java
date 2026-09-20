package com.rentalshop.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One accessory (a pant, a dupatta, a jewellery set) attached to ONE booked
 * item at booking time -- see the "Add accessory" button on each item row of
 * the New Booking form. A booking row can carry several, across several
 * categories; a bill with four dresses has four independent accessory lists,
 * one per dress, never one shared list for the whole bill. That's the whole
 * point of hanging this off the booking row rather than off groupId.
 *
 * Deliberately a SNAPSHOT, not a live join: itemCode/itemName/category are
 * copied off the inventory item at the moment of booking and never updated
 * afterwards. [itemId] is kept alongside them so the app can still deep-link
 * to the live product, but every field the bill actually renders comes from
 * this row. Three reasons, all of which have bitten shops before:
 *
 *   1. An item can be renamed or re-categorised later; an old bill must keep
 *      showing what actually went out that day, not today's spelling.
 *   2. An item can be soft-deleted (ItemService.deleteItem) -- a bill from
 *      two years ago would otherwise render blanks for a retired accessory.
 *   3. Rendering a bill or the booking-history list costs no per-accessory
 *      item lookup at all.
 *
 * Note what this deliberately is NOT: attaching an accessory here does NOT
 * book it or block its calendar the way the main item's Booking row does.
 * Accessories are recorded as "these went out with this dress" for the
 * bill/handover checklist. If the shop ever needs an accessory to be
 * double-booking-proof too, the right move is to book it as its own item
 * row (which the multi-item form already supports), not to grow this table.
 */
@Entity
@Table(
        name = "booking_accessories",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_booking_accessory",
                columnNames = {"booking_id", "item_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class BookingAccessory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The booked item this accessory goes out with. Owning side of
     * Booking.accessories -- set via Booking.addAccessory, never directly,
     * so the two sides can't drift apart in the same persistence context.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /** The inventory item this accessory is. Kept for deep-linking only -- see class doc. */
    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "item_code", nullable = false, length = 40)
    private String itemCode;

    @Column(name = "item_name", nullable = false, length = 150)
    private String itemName;

    /**
     * Which bucket staff picked this under on the form. Resolved from the
     * item's own category/sub-category where possible, falling back to what
     * the client said -- see BookingService.attachAccessories.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccessoryCategory category;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
