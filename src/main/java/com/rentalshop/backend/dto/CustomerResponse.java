package com.rentalshop.backend.dto;

import com.rentalshop.backend.entity.Customer;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CustomerResponse {
    private Long id;
    private String name;
    private String phone;
    private String address;
    private String notes;

    public static CustomerResponse from(Customer c) {
        return CustomerResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .phone(c.getPhone())
                .address(c.getAddress())
                .notes(c.getNotes())
                .build();
    }
}
