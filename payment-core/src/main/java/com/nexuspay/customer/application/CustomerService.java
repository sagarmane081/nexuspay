package com.nexuspay.customer.application;

import com.nexuspay.common.error.BusinessRuleViolationException;
import com.nexuspay.common.error.EntityNotFoundException;
import com.nexuspay.customer.domain.Customer;
import com.nexuspay.customer.domain.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, Clock clock) {
        this.customers = customers;
        this.clock = clock;
    }

    @Transactional
    public Customer open(String fullName, String email) {
        if (customers.existsByEmail(email.toLowerCase())) {
            throw new BusinessRuleViolationException("a customer already exists with email " + email);
        }
        return customers.save(Customer.open(fullName, email, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Customer require(UUID customerId) {
        return customers.findById(customerId)
                .orElseThrow(() -> new EntityNotFoundException("Customer", customerId));
    }
}
