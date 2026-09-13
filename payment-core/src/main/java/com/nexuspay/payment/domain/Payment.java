package com.nexuspay.payment.domain;

import com.nexuspay.common.error.BusinessRuleViolationException;
import com.nexuspay.common.error.ErrorCode;
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
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

/**
 * A payment, and the state machine that governs it.
 * <p>
 * Every transition goes through {@link #transitionTo}, so there is exactly one
 * place where a status changes and exactly one place the rules can be bypassed —
 * which is to say, none.
 */
@Entity
@Table(name = "payment")
public class Payment extends AssignedIdEntity {

    @Id
    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "terminal_id", updatable = false)
    private UUID terminalId;

    @Column(name = "card_id", nullable = false, updatable = false)
    private UUID cardId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "authorized_amount", precision = 18, scale = 4)
    private BigDecimal authorizedAmount;

    @Column(name = "captured_amount", precision = 18, scale = 4)
    private BigDecimal capturedAmount;

    @Column(name = "refunded_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal refundedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "mcc", nullable = false, length = 4)
    private String mcc;

    @Column(name = "auth_code", length = 6)
    private String authCode;

    @Column(name = "risk_decision", length = 10)
    private String riskDecision;

    @Column(name = "risk_score")
    private Short riskScore;

    @Column(name = "decline_reason", length = 200)
    private String declineReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    protected Payment() {
        // for JPA
    }

    private Payment(UUID paymentId, UUID correlationId, UUID merchantId, UUID terminalId,
                    UUID cardId, Money amount, String mcc, LocalDate businessDate, Instant now) {
        this.paymentId = paymentId;
        this.correlationId = correlationId;
        this.merchantId = merchantId;
        this.terminalId = terminalId;
        this.cardId = cardId;
        this.amount = amount.amount();
        this.currency = amount.currency().getCurrencyCode();
        this.refundedAmount = BigDecimal.ZERO;
        this.status = PaymentStatus.RECEIVED;
        this.mcc = mcc;
        this.businessDate = businessDate;
        this.createdAt = now;
    }

    public static Payment initiate(UUID correlationId, UUID merchantId, UUID terminalId,
                                   UUID cardId, Money amount, String mcc,
                                   LocalDate businessDate, Instant now) {
        return new Payment(UuidV7.generate(), correlationId, merchantId, terminalId,
                cardId, amount, mcc, businessDate, now);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void validate() {
        transitionTo(PaymentStatus.VALIDATED);
    }

    public void beginRiskCheck() {
        transitionTo(PaymentStatus.RISK_CHECK);
    }

    public void recordRiskDecision(String decision, int score) {
        this.riskDecision = decision;
        this.riskScore = (short) score;
    }

    public void authorize(Money authorizedAmount, String authCode, Instant now) {
        requireSameCurrency(authorizedAmount);
        if (authorizedAmount.isGreaterThan(amount())) {
            throw new BusinessRuleViolationException(
                    "cannot authorize %s against a requested amount of %s".formatted(authorizedAmount, amount()));
        }
        transitionTo(PaymentStatus.AUTHORIZED);
        this.authorizedAmount = authorizedAmount.amount();
        this.authCode = authCode;
        this.authorizedAt = now;
    }

    public void decline(String reason) {
        transitionTo(PaymentStatus.DECLINED);
        // The database refuses a DECLINED row without one: a decline nobody can
        // explain is a support case nobody can close.
        this.declineReason = reason;
    }

    /**
     * Partial capture is permitted — a hotel authorizing ¥30,000 and capturing
     * ¥18,000 is routine. Over-capture is not.
     */
    public void capture(Money captureAmount, Instant now) {
        requireSameCurrency(captureAmount);
        if (!captureAmount.isPositive()) {
            throw new BusinessRuleViolationException("capture amount must be positive");
        }
        if (captureAmount.isGreaterThan(authorizedAmount())) {
            throw new BusinessRuleViolationException(
                    "cannot capture %s against an authorization of %s".formatted(
                            captureAmount, authorizedAmount()));
        }
        transitionTo(PaymentStatus.CAPTURED);
        this.capturedAmount = captureAmount.amount();
        this.capturedAt = now;
    }

    public void reverse() {
        if (status == PaymentStatus.SETTLED || status == PaymentStatus.CLEARED) {
            throw new BusinessRuleViolationException(ErrorCode.INVALID_STATE_TRANSITION,
                    "payment %s has reached %s; cash has moved or is moving, so it must be refunded rather than reversed"
                            .formatted(paymentId, status));
        }
        transitionTo(PaymentStatus.REVERSED);
    }

    /** Applied by the expiry sweep when the issuer's guarantee lapses uncaptured. */
    public void expire() {
        transitionTo(PaymentStatus.EXPIRED);
    }

    /** Driven by clearing in Phase 4.2. */
    public void markCleared() {
        transitionTo(PaymentStatus.CLEARED);
    }

    /** Driven by settlement in Phase 4.3. */
    public void markSettled() {
        transitionTo(PaymentStatus.SETTLED);
    }

    public void fail() {
        transitionTo(PaymentStatus.FAILED);
    }

    /**
     * Records a refund against this payment.
     * <p>
     * The cumulative check is the point: any single refund may look reasonable
     * while the total exceeds what was captured. Callers must hold a write lock
     * on this row — see {@code PaymentRepository.findByIdForUpdate} — because
     * two concurrent refunds would otherwise both read the same
     * {@code refundedAmount} and both pass.
     */
    public void refund(Money refundAmount) {
        requireSameCurrency(refundAmount);
        if (status != PaymentStatus.SETTLED) {
            throw new BusinessRuleViolationException(ErrorCode.INVALID_STATE_TRANSITION,
                    "payment %s is %s; only a settled payment can be refunded".formatted(paymentId, status));
        }
        if (!refundAmount.isPositive()) {
            throw new BusinessRuleViolationException("refund amount must be positive");
        }

        Money newTotal = refundedAmount().plus(refundAmount);
        if (newTotal.isGreaterThan(capturedAmount())) {
            throw new BusinessRuleViolationException(
                    "refunding %s would bring total refunds to %s against a capture of %s".formatted(
                            refundAmount, newTotal, capturedAmount()));
        }

        this.refundedAmount = newTotal.amount();
        // Fully refunded is a different fact from partially refunded, and only
        // the former is terminal.
        if (newTotal.compareTo(capturedAmount()) == 0) {
            transitionTo(PaymentStatus.REFUNDED);
        }
    }

    private void transitionTo(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessRuleViolationException(ErrorCode.INVALID_STATE_TRANSITION,
                    "payment %s cannot move from %s to %s".formatted(paymentId, status, target));
        }
        this.status = target;
    }

    private void requireSameCurrency(Money other) {
        if (!other.currency().getCurrencyCode().equals(currency)) {
            throw new BusinessRuleViolationException(ErrorCode.CURRENCY_MISMATCH,
                    "payment %s is in %s but the operation is in %s".formatted(
                            paymentId, currency, other.currency().getCurrencyCode()));
        }
    }

    // ------------------------------------------------------------------

    @Override
    public UUID getId() {
        return paymentId;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public UUID correlationId() {
        return correlationId;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public UUID terminalId() {
        return terminalId;
    }

    public UUID cardId() {
        return cardId;
    }

    public Money amount() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public Money authorizedAmount() {
        return authorizedAmount == null ? null : Money.of(authorizedAmount, Currency.getInstance(currency));
    }

    public Money capturedAmount() {
        return capturedAmount == null ? null : Money.of(capturedAmount, Currency.getInstance(currency));
    }

    public Money refundedAmount() {
        return Money.of(refundedAmount, Currency.getInstance(currency));
    }

    public PaymentStatus status() {
        return status;
    }

    public String mcc() {
        return mcc;
    }

    public String authCode() {
        return authCode;
    }

    public String riskDecision() {
        return riskDecision;
    }

    public Short riskScore() {
        return riskScore;
    }

    public String declineReason() {
        return declineReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant authorizedAt() {
        return authorizedAt;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public LocalDate businessDate() {
        return businessDate;
    }
}
