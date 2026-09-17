package com.rentalshop.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateCustomerRequest {

    @NotBlank
    private String name;

    /**
     * Not enforced unique -- schema.sql only indexes phone (idx_customer_phone),
     * it doesn't constrain it, since family members booking under a shared
     * household number is a normal case for this shop, not a data error.
     */
    @NotBlank
    private String phone;

    private String address;

    private String notes;
}
