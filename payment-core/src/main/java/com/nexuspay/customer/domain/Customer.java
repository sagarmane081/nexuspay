package com.nexuspay.customer.domain;

import com.nexuspay.common.id.UuidV7;
import com.nexuspay.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer")
public class Customer extends AssignedIdEntity {

    @Id
    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    // STRING, never ORDINAL: ordinal storage means reordering the enum silently
    // rewrites the meaning of every existing row.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CustomerStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Customer() {
        // for JPA
    }

    private Customer(UUID customerId, String fullName, String email, Instant now) {
        this.customerId = customerId;
        this.fullName = fullName;
        this.email = email;
        this.status = CustomerStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Customer open(String fullName, String email, Instant now) {
        return new Customer(UuidV7.generate(), fullName, email.toLowerCase(), now);
    }

    @Override
    public UUID getId() {
        return customerId;
    }

    public UUID customerId() {
        return customerId;
    }

    public String fullName() {
        return fullName;
    }

    public String email() {
        return email;
    }

    public CustomerStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
