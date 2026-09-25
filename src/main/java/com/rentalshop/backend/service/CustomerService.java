package com.rentalshop.backend.service;

import com.rentalshop.backend.dto.CreateCustomerRequest;
import com.rentalshop.backend.dto.CustomerResponse;
import com.rentalshop.backend.dto.UpdateCustomerRequest;
import com.rentalshop.backend.entity.Customer;
import com.rentalshop.backend.repository.BookingRepository;
import com.rentalshop.backend.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Customer CRUD (development plan: "Customer CRUD endpoints"). Same shape
 * as ItemService -- hard delete, blocked when booking history still
 * references the customer.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final AuditLogService auditLogService;
    private final BookingRepository bookingRepository;

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

    /** Returns empty if no customer exists with this id. */
    @Transactional
    public Optional<CustomerResponse> updateCustomer(Long id, UpdateCustomerRequest req) {
        return customerRepository.findById(id)
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
     * Hard delete. Blocked (409 INVALID_STATE) when the customer still has
     * booking history -- bookings.customer_id has a plain FK with no
     * ON DELETE clause in schema.sql precisely so a historical booking can
     * never end up pointing at a row that no longer exists, so that case is
     * checked explicitly here to fail with a clear message rather than a
     * raw DB constraint-violation error. A customer with no bookings can be
     * deleted freely.
     */
    @Transactional
    public boolean deleteCustomer(Long id) {
        Customer customer = customerRepository.findById(id).orElse(null);
        if (customer == null) {
            return false;
        }

        if (bookingRepository.existsByCustomerId(id)) {
            throw new IllegalStateException(
                    "Cannot delete customer " + customer.getName() +
                    ": they have booking history on record.");
        }

        auditLogService.recordCustomerDeleted(customer);
        customerRepository.delete(customer);
        return true;
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> listCustomers(String query) {
        return customerRepository.search(query).stream()
                .map(CustomerResponse::from)
                .toList();
    }
}
