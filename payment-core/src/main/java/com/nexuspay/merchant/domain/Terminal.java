package com.nexuspay.merchant.domain;

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
@Table(name = "terminal")
public class Terminal extends AssignedIdEntity {

    @Id
    @Column(name = "terminal_id", nullable = false, updatable = false)
    private UUID terminalId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "terminal_ref", nullable = false, length = 50)
    private String terminalRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TerminalStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Terminal() {
        // for JPA
    }

    private Terminal(UUID terminalId, UUID merchantId, String terminalRef, Instant now) {
        this.terminalId = terminalId;
        this.merchantId = merchantId;
        this.terminalRef = terminalRef;
        this.status = TerminalStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Terminal register(UUID merchantId, String terminalRef, Instant now) {
        return new Terminal(UuidV7.generate(), merchantId, terminalRef, now);
    }

    @Override
    public UUID getId() {
        return terminalId;
    }

    public UUID terminalId() {
        return terminalId;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public String terminalRef() {
        return terminalRef;
    }

    public TerminalStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
