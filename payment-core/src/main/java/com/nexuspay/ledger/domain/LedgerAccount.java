package com.nexuspay.ledger.domain;

import com.nexuspay.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** An account in the chart of accounts. Seeded by V4; read-only at runtime. */
@Entity
@Table(name = "ledger_account")
public class LedgerAccount extends AssignedIdEntity {

    @Id
    @Column(name = "ledger_account_id", nullable = false, updatable = false)
    private UUID ledgerAccountId;

    @Column(name = "account_code", nullable = false, updatable = false, length = 50)
    private String accountCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false, length = 20)
    private LedgerAccountType accountType;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "description", nullable = false, updatable = false, length = 200)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerAccount() {
    }

    @Override
    public UUID getId() {
        return ledgerAccountId;
    }

    public UUID ledgerAccountId() {
        return ledgerAccountId;
    }

    public String accountCode() {
        return accountCode;
    }

    public LedgerAccountType accountType() {
        return accountType;
    }

    public String currency() {
        return currency;
    }
}
