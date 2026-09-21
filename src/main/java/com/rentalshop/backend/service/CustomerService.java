package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.CreateCustomerRequest;
import com.rentalshop.backend.dto.CustomerResponse;
import com.rentalshop.backend.dto.UpdateCustomerRequest;
import com.rentalshop.backend.entity.Customer;
import com.rentalshop.backend.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Customer CRUD (development plan: "Customer CRUD endpoints"). Same shape
 * as ItemService -- soft delete only, no hard deletes, since a booking's
 * customer_id foreign key must keep resolving for historical bookings even
 * after a customer record is retired.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public CustomerResponse createCustomer(CreateCustomerRequest req) {
        String idempotencyKey = req.getIdempotencyKey() == null || req.getIdempotencyKey().isBlank()
                ? null : req.getIdempotencyKey().trim();
        if (idempotencyKey != null) {
            // Phone numbers are deliberately not unique (shared household
            // numbers), so this key is the ONLY thing that stops a replayed
            // create from producing a duplicate customer.
            var existing = customerRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return CustomerResponse.from(existing.get());
            }
        }

        Customer customer = new Customer();
        customer.setName(req.getName());
        customer.setPhone(req.getPhone());
        customer.setAddress(req.getAddress());
        customer.setNotes(req.getNotes());
        customer.setIdempotencyKey(idempotencyKey);

        Customer saved = customerRepository.save(customer);
        auditLogService.recordCustomerCreated(saved);
        return CustomerResponse.from(saved);
    }

    /** Returns empty if no non-deleted customer exists with this id. */
    @Transactional
    public Optional<CustomerResponse> updateCustomer(Long id, UpdateCustomerRequest req) {
        return customerRepository.findById(id)
                .filter(c -> !c.isDeleted())
                .map(customer -> {
                    // Captured before any mutation below, same reasoning as
                    // ItemService.updateItem's before-snapshot.
                    java.util.Map<String, Object> before = auditLogService.snapshotCustomer(customer);

                    customer.setName(req.getName());
                    customer.setPhone(req.getPhone());
                    customer.setAddress(req.getAddress());
                    customer.setNotes(req.getNotes());

                    Customer saved = customerRepository.save(customer);
                    auditLogService.recordCustomerUpdated(before, saved);
                    return CustomerResponse.from(saved);
                });
    }

    /**
     * Soft delete only -- bookings referencing this customer (past or
     * future) must keep resolving customer_id for history/receipts, so a
     * hard DELETE is never used here, same reasoning as ItemService.deleteItem.
     */
    @Transactional
    public boolean deleteCustomer(Long id) {
        return customerRepository.findById(id)
                .filter(c -> !c.isDeleted())
                .map(customer -> {
                    customer.setDeleted(true);
                    customerRepository.save(customer);
                    auditLogService.recordCustomerDeleted(customer);
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> listCustomers(String query, boolean includeDeleted) {
        return customerRepository.search(query, includeDeleted).stream()
                .map(CustomerResponse::from)
                .toList();
    }
}
