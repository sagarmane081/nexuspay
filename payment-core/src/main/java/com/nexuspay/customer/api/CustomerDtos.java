package com.nexuspay.customer.api;

import com.nexuspay.customer.domain.Customer;
import com.nexuspay.customer.domain.CustomerStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class CustomerDtos {

    private CustomerDtos() {
    }

    public record CreateCustomerRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Email @Size(max = 320) String email) {
    }

    public record CustomerResponse(
            UUID customerId,
            String fullName,
            String email,
            CustomerStatus status,
            Instant createdAt) {

        public static CustomerResponse from(Customer customer) {
            return new CustomerResponse(
                    customer.customerId(),
                    customer.fullName(),
                    customer.email(),
                    customer.status(),
                    customer.createdAt());
        }
    }
}
