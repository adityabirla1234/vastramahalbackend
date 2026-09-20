package com.rentalshop.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * Unlike UpdateItemRequest, there's no {@code version} field here --
 * {@code customers} has no version column in schema.sql, so this is a plain
 * last-write-wins update, not optimistic-locked. Customer edits (fixing a
 * phone number, adding a note) don't carry the same "two devices silently
 * clobbering each other's change" risk that item pricing/status does, so
 * that's an acceptable simplification for V1 -- worth revisiting only if
 * customer edits turn out to collide in practice.
 */
@Getter
@Setter
public class UpdateCustomerRequest {

    @NotBlank
    private String name;

    /** Same ten-digits-only rule as CreateCustomerRequest.phone -- see its Javadoc. */
    @NotBlank
    @Pattern(regexp = "\\d{10}", message = "Phone number must be exactly 10 digits")
    private String phone;

    private String address;

    private String notes;
}
