package com.rentalshop.backend.controller;

import com.rentalshop.backend.dto.CreateCustomerRequest;
import com.rentalshop.backend.dto.CustomerResponse;
import com.rentalshop.backend.dto.UpdateCustomerRequest;
import com.rentalshop.backend.entity.Owner;
import com.rentalshop.backend.repository.CustomerRepository;
import com.rentalshop.backend.security.RequireRole;
import com.rentalshop.backend.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerRepository customerRepository;
    private final CustomerService customerService;

    /**
     * Customers list screen. query is optional and matches name OR phone
     * (see CustomerRepository.search) -- covers both "type the customer's
     * name" and "type the phone number they're calling from" lookup flows.
     */
    @GetMapping
    public ResponseEntity<List<CustomerResponse>> list(@RequestParam(required = false) String query) {
        return ResponseEntity.ok(customerService.listCustomers(query));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponse> findById(@PathVariable Long id) {
        return customerRepository.findById(id)
                .map(CustomerResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.createCustomer(request));
    }

    @PutMapping("/{id}")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<CustomerResponse> update(@PathVariable Long id,
                                                     @Valid @RequestBody UpdateCustomerRequest request) {
        return customerService.updateCustomer(id, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Hard delete — see CustomerService.deleteCustomer. 404 if the
     * customer doesn't exist, 409 INVALID_STATE if they still have booking
     * history.
     */
    @DeleteMapping("/{id}")
    @RequireRole(Owner.Role.ADMIN)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean deleted = customerService.deleteCustomer(id);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
