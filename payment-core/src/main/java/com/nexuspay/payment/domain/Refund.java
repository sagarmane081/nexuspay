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
 * A refund is a separate record, never an edit of the original payment. The
 * original charge really happened and stays in history permanently.
 */
@Entity
@Table(name = "refund")
public class Refund extends AssignedIdEntity {

    @Id
    @Column(name = "refund_id", nullable = false, updatable = false)
    private UUID refundId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reason", nullable = false, length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Refund() {
    }

    private Refund(UUID refundId, UUID paymentId, Money amount, String reason, Instant now) {
        this.refundId = refundId;
        this.paymentId = paymentId;
        this.amount = amount.amount();
        this.currency = amount.currency().getCurrencyCode();
        this.reason = reason;
        this.createdAt = now;
    }

    public static Refund of(UUID paymentId, Money amount, String reason, Instant now) {
        return new Refund(UuidV7.generate(), paymentId, amount, reason, now);
    }

    @Override
    public UUID getId() {
        return refundId;
    }

    public UUID refundId() {
        return refundId;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public Money amount() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public String reason() {
        return reason;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
