package com.nexuspay.account.domain;

import com.nexuspay.common.id.UuidV7;
import com.nexuspay.common.money.Money;
import com.nexuspay.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * A funding account.
 * <p>
 * Note what is absent: there is no available-balance column. Available balance
 * is {@code balance - sum(active holds)}, derived at read time, because an
 * authorization moves no money — see business-requirements.md section 4.
 * Storing it would create a second source of truth that drifts from the first.
 * <p>
 * The amount and currency are stored as separate columns rather than as an
 * embedded {@link Money}, which keeps {@code Money} a pure record with no JPA
 * annotations. {@link #balance()} reassembles it at the boundary.
 */
@Entity
@Table(name = "account")
public class Account extends AssignedIdEntity {

    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 18, scale = 4)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // for JPA
    }

    private Account(UUID accountId, UUID customerId, AccountType accountType, Money openingBalance, Instant now) {
        this.accountId = accountId;
        this.customerId = customerId;
        this.accountType = accountType;
        this.currency = openingBalance.currency().getCurrencyCode();
        this.balance = openingBalance.amount();
        this.status = AccountStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Account open(UUID customerId, AccountType accountType, Money openingBalance, Instant now) {
        return new Account(UuidV7.generate(), customerId, accountType, openingBalance, now);
    }

    @Override
    public UUID getId() {
        return accountId;
    }

    public UUID accountId() {
        return accountId;
    }

    public UUID customerId() {
        return customerId;
    }

    public AccountType accountType() {
        return accountType;
    }

    public Money balance() {
        return Money.of(balance, Currency.getInstance(currency));
    }

    public Currency currency() {
        return Currency.getInstance(currency);
    }

    public AccountStatus status() {
        return status;
    }

    public boolean isUsable() {
        return status == AccountStatus.ACTIVE;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
