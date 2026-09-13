package com.nexuspay.ledger.domain;

import com.nexuspay.common.id.UuidV7;
import com.nexuspay.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A group of entries that balance together.
 * <p>
 * Append-only: V4 attaches a trigger that rejects any UPDATE or DELETE. A
 * mistake is corrected by posting a REVERSAL journal, never by editing history.
 */
@Entity
@Table(name = "journal")
public class Journal extends AssignedIdEntity {

    @Id
    @Column(name = "journal_id", nullable = false, updatable = false)
    private UUID journalId;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "payment_id", updatable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "journal_type", nullable = false, updatable = false, length = 20)
    private JournalType journalType;

    @Column(name = "description", nullable = false, updatable = false, length = 200)
    private String description;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    protected Journal() {
    }

    private Journal(UUID journalId, UUID correlationId, UUID paymentId, JournalType journalType,
                    String description, Instant postedAt, LocalDate businessDate) {
        this.journalId = journalId;
        this.correlationId = correlationId;
        this.paymentId = paymentId;
        this.journalType = journalType;
        this.description = description;
        this.postedAt = postedAt;
        this.businessDate = businessDate;
    }

    public static Journal of(UUID correlationId, UUID paymentId, JournalType journalType,
                             String description, Instant postedAt, LocalDate businessDate) {
        return new Journal(UuidV7.generate(), correlationId, paymentId, journalType,
                description, postedAt, businessDate);
    }

    @Override
    public UUID getId() {
        return journalId;
    }

    public UUID journalId() {
        return journalId;
    }

    public UUID correlationId() {
        return correlationId;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public JournalType journalType() {
        return journalType;
    }

    public String description() {
        return description;
    }

    public Instant postedAt() {
        return postedAt;
    }

    public LocalDate businessDate() {
        return businessDate;
    }
}
