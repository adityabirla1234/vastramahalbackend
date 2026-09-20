package com.rentalshop.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateCustomerRequest {

    @NotBlank
    private String name;

    /**
     * Exactly ten digits, nothing else -- no spaces, no +91, no dashes.
     * The app enforces the same rule at the keyboard (digits only, capped
     * at 10), but it's repeated here because the app is not the only thing
     * that can POST to this endpoint, and because a phone number that is
     * sometimes "9876543210" and sometimes "+91 98765 43210" makes the
     * customer search staff rely on unreliable.
     *
     * Not enforced unique -- schema.sql only indexes phone (idx_customer_phone),
     * it doesn't constrain it, since family members booking under a shared
     * household number is a normal case for this shop, not a data error.
     */
    @NotBlank
    @Pattern(regexp = "\\d{10}", message = "Phone number must be exactly 10 digits")
    private String phone;

    private String address;

    private String notes;
}
