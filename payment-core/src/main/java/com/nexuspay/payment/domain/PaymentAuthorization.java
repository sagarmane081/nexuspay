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
 * The issuer's answer, kept as a record of what actually happened rather than as
 * flags on the payment. Phase 4.1 maps an ISO 8583 0110 message onto this row.
 */
@Entity
@Table(name = "payment_authorization")
public class PaymentAuthorization extends AssignedIdEntity {

    @Id
    @Column(name = "authorization_id", nullable = false, updatable = false)
    private UUID authorizationId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "approved", nullable = false)
    private boolean approved;

    @Column(name = "auth_code", length = 6)
    private String authCode;

    @Column(name = "issuer_response_code", nullable = false, length = 4)
    private String issuerResponseCode;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentAuthorization() {
    }

    private PaymentAuthorization(UUID id, UUID paymentId, Money amount, boolean approved,
                                 String authCode, String issuerResponseCode,
                                 Instant expiresAt, Instant now) {
        this.authorizationId = id;
        this.paymentId = paymentId;
        this.amount = amount.amount();
        this.currency = amount.currency().getCurrencyCode();
        this.approved = approved;
        this.authCode = authCode;
        this.issuerResponseCode = issuerResponseCode;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public static PaymentAuthorization approved(UUID paymentId, Money amount, String authCode,
                                                String responseCode, Instant expiresAt, Instant now) {
        return new PaymentAuthorization(UuidV7.generate(), paymentId, amount, true,
                authCode, responseCode, expiresAt, now);
    }

    public static PaymentAuthorization declined(UUID paymentId, Money amount,
                                                String responseCode, Instant expiresAt, Instant now) {
        // authCode stays null: the database constraint refuses a decline that
        // carries an approval code.
        return new PaymentAuthorization(UuidV7.generate(), paymentId, amount, false,
                null, responseCode, expiresAt, now);
    }

    @Override
    public UUID getId() {
        return authorizationId;
    }

    public UUID authorizationId() {
        return authorizationId;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public Money amount() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public boolean isApproved() {
        return approved;
    }

    public String authCode() {
        return authCode;
    }

    public String issuerResponseCode() {
        return issuerResponseCode;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
