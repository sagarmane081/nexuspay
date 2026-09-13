package com.nexuspay.ledger.domain;

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

/** One line of a journal. Immutable once written. */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry extends AssignedIdEntity {

    @Id
    @Column(name = "entry_id", nullable = false, updatable = false)
    private UUID entryId;

    @Column(name = "journal_id", nullable = false, updatable = false)
    private UUID journalId;

    @Column(name = "ledger_account_id", nullable = false, updatable = false)
    private UUID ledgerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false, length = 6)
    private EntryDirection direction;

    @Column(name = "amount", nullable = false, updatable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    protected LedgerEntry() {
    }

    private LedgerEntry(UUID entryId, UUID journalId, UUID ledgerAccountId,
                        EntryDirection direction, Money amount, Instant postedAt) {
        this.entryId = entryId;
        this.journalId = journalId;
        this.ledgerAccountId = ledgerAccountId;
        this.direction = direction;
        this.amount = amount.amount();
        this.currency = amount.currency().getCurrencyCode();
        this.postedAt = postedAt;
    }

    public static LedgerEntry of(UUID journalId, UUID ledgerAccountId,
                                 EntryDirection direction, Money amount, Instant postedAt) {
        return new LedgerEntry(UuidV7.generate(), journalId, ledgerAccountId, direction, amount, postedAt);
    }

    @Override
    public UUID getId() {
        return entryId;
    }

    public UUID entryId() {
        return entryId;
    }

    public UUID journalId() {
        return journalId;
    }

    public UUID ledgerAccountId() {
        return ledgerAccountId;
    }

    public EntryDirection direction() {
        return direction;
    }

    public Money amount() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public boolean isDebit() {
        return direction == EntryDirection.DEBIT;
    }
}
