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

@Entity
@Table(name = "capture")
public class Capture extends AssignedIdEntity {

    @Id
    @Column(name = "capture_id", nullable = false, updatable = false)
    private UUID captureId;

    /**
     * UNIQUE in the schema. Even if the application guard is bypassed or two
     * capture requests race, the second insert fails rather than double-charging
     * the cardholder.
     */
    @Column(name = "authorization_id", nullable = false, updatable = false)
    private UUID authorizationId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant capturedAt;

    protected Capture() {
    }

    private Capture(UUID captureId, UUID authorizationId, UUID paymentId, Money amount, Instant now) {
        this.captureId = captureId;
        this.authorizationId = authorizationId;
        this.paymentId = paymentId;
        this.amount = amount.amount();
        this.currency = amount.currency().getCurrencyCode();
        this.capturedAt = now;
    }

    public static Capture of(UUID authorizationId, UUID paymentId, Money amount, Instant now) {
        return new Capture(UuidV7.generate(), authorizationId, paymentId, amount, now);
    }

    @Override
    public UUID getId() {
        return captureId;
    }

    public UUID captureId() {
        return captureId;
    }

    public Money amount() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public Instant capturedAt() {
        return capturedAt;
    }
}
