package com.nexuspay.payment.domain;

import com.nexuspay.common.id.UuidV7;
import com.nexuspay.common.money.Money;
import com.nexuspay.common.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * Cancels an authorization or capture before settlement. No cash ever moved, so
 * there is nothing to send back — the hold is simply released.
 */
@Entity
@Table(name = "reversal")
public class Reversal extends AssignedIdEntity {

    @Id
    @Column(name = "reversal_id", nullable = false, updatable = false)
    private UUID reversalId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "authorization_id", nullable = false, updatable = false)
    private UUID authorizationId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reason", nullable = false, length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Reversal() {
    }

    private Reversal(UUID reversalId, UUID paymentId, UUID authorizationId,
                     Money amount, String reason, Instant now) {
        this.reversalId = reversalId;
        this.paymentId = paymentId;
        this.authorizationId = authorizationId;
        this.amount = amount.amount();
        this.currency = amount.currency().getCurrencyCode();
        this.reason = reason;
        this.createdAt = now;
    }

    public static Reversal of(UUID paymentId, UUID authorizationId, Money amount, String reason, Instant now) {
        return new Reversal(UuidV7.generate(), paymentId, authorizationId, amount, reason, now);
    }

    @Override
    public UUID getId() {
        return reversalId;
    }

    public UUID reversalId() {
        return reversalId;
    }

    public Money amount() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public String reason() {
        return reason;
    }
}
