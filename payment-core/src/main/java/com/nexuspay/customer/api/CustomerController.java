package com.nexuspay.customer.api;

import com.nexuspay.customer.api.CustomerDtos.CreateCustomerRequest;
import com.nexuspay.customer.api.CustomerDtos.CustomerResponse;
import com.nexuspay.customer.application.CustomerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request,
                                                   UriComponentsBuilder uri) {
        CustomerResponse body = CustomerResponse.from(
                customers.open(request.fullName(), request.email()));

        return ResponseEntity
                .created(uri.path("/api/v1/customers/{id}").build(body.customerId()))
                .body(body);
    }

    @GetMapping("/{customerId}")
    public CustomerResponse get(@PathVariable UUID customerId) {
        return CustomerResponse.from(customers.require(customerId));
    }
}
