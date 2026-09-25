package com.rentalshop.backend.repository;

import com.rentalshop.backend.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByIdempotencyKey(String idempotencyKey);

    /**
     * Backs the customers list screen -- same shape as ItemRepository.search.
     * query is optional (pass null/blank to list everyone) and matches
     * against name OR phone, since staff on the shop floor will look
     * customers up by either.
     */
    @Query("""
           select c from Customer c
           where (:query is null or :query = ''
                  or lower(c.name) like lower(concat('%', :query, '%'))
                  or c.phone like concat('%', :query, '%'))
           order by c.name
           """)
    List<Customer> search(@Param("query") String query);
}
