package com.rentalshop.backend.entity;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The accessory buckets staff can attach to a booked item on the New
 * Booking form ("Add accessory"): a pant, a dupatta and/or jewellery that
 * goes out alongside the main dress.
 *
 * These are a fixed, shop-level vocabulary -- NOT free text and NOT read
 * from whatever distinct values happen to exist in items.category. That's
 * deliberate: the form has to offer the same three choices every time,
 * even on a fresh database with no accessory items entered yet, and a
 * booking's stored accessory list has to stay readable years later even if
 * someone later renames the inventory category it was picked from.
 *
 * {@link #matchTerms()} is what bridges the two worlds: the shop's own
 * inventory rows spell these categories however staff typed them
 * ("Jewelry", "jewellery", "Dupatta "), so an item is offered under a
 * bucket when its category OR sub-category matches any of these terms,
 * case-insensitively and trimmed. Add a spelling here if the shop uses one
 * this list doesn't cover yet -- it's the one place that mapping lives.
 */
public enum AccessoryCategory {

    PANT("Pant", "pant", "pants", "patiala", "salwar", "bottom", "bottoms"),
    JEWELLERY("Jewellery", "jewellery", "jewelry", "jewellary", "jewel", "jewels", "ornament", "ornaments"),
    DUPATTA("Dupatta", "dupatta", "duppatta", "dupata", "odhni", "chunni", "stole");

    private final String label;
    private final Set<String> matchTerms;

    AccessoryCategory(String label, String... matchTerms) {
        this.label = label;
        this.matchTerms = new LinkedHashSet<>(Arrays.asList(matchTerms));
    }

    /** Human-readable name, exactly as it should appear on the booking form. */
    public String label() {
        return label;
    }

    /** Lower-case inventory category/sub-category spellings that map to this bucket. */
    public Set<String> matchTerms() {
        return matchTerms;
    }

    /**
     * Which bucket an inventory item belongs to, judged from its own
     * category/sub-category text. Empty when the item isn't an accessory at
     * all (a dress, say) or is filed under a spelling {@link #matchTerms()}
     * doesn't know yet -- callers decide whether that's an error or a
     * fall-back-to-what-the-client-said situation.
     */
    public static Optional<AccessoryCategory> match(String... itemFields) {
        if (itemFields == null) {
            return Optional.empty();
        }
        for (String raw : itemFields) {
            if (raw == null) {
                continue;
            }
            String normalized = raw.trim().toLowerCase();
            if (normalized.isEmpty()) {
                continue;
            }
            for (AccessoryCategory category : values()) {
                if (category.matchTerms.contains(normalized)) {
                    return Optional.of(category);
                }
            }
        }
        return Optional.empty();
    }
}
