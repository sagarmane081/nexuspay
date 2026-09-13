package com.nexuspay.card.domain;

import com.nexuspay.common.error.BusinessRuleViolationException;
import com.nexuspay.common.error.ErrorCode;
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
import java.time.YearMonth;
import java.util.UUID;

/**
 * A payment card.
 * <p>
 * There is no field for the card number. The PAN is converted to a token and a
 * masked display form at the edge and then discarded — you cannot leak what you
 * never stored.
 */
@Entity
@Table(name = "card")
public class Card extends AssignedIdEntity {

    @Id
    @Column(name = "card_id", nullable = false, updatable = false)
    private UUID cardId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "card_token", nullable = false, updatable = false, length = 64)
    private String cardToken;

    @Column(name = "pan_masked", nullable = false, updatable = false, length = 19)
    private String panMasked;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_scheme", nullable = false, updatable = false, length = 20)
    private CardScheme cardScheme;

    @Column(name = "expiry_month", nullable = false)
    private short expiryMonth;

    @Column(name = "expiry_year", nullable = false)
    private short expiryYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CardStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Card() {
        // for JPA
    }

    private Card(UUID cardId, UUID accountId, String cardToken, String panMasked,
                 CardScheme cardScheme, int expiryMonth, int expiryYear, Instant now) {
        this.cardId = cardId;
        this.accountId = accountId;
        this.cardToken = cardToken;
        this.panMasked = panMasked;
        this.cardScheme = cardScheme;
        this.expiryMonth = (short) expiryMonth;
        this.expiryYear = (short) expiryYear;
        this.status = CardStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Card issue(UUID accountId, String cardToken, String panMasked,
                             CardScheme cardScheme, int expiryMonth, int expiryYear, Instant now) {
        return new Card(UuidV7.generate(), accountId, cardToken, panMasked,
                cardScheme, expiryMonth, expiryYear, now);
    }

    // ------------------------------------------------------------------
    // The rule that matters: can this card pay?
    // ------------------------------------------------------------------

    /**
     * A card is valid through the <em>last day of its expiry month</em>. A card
     * marked 12/2026 works on 2026-12-31 and fails on 2027-01-01.
     * <p>
     * This is the off-by-one that bites everyone: comparing against the first
     * of the month kills every card a month early, and thousands of legitimate
     * payments with it.
     */
    public boolean isExpiredOn(LocalDate date) {
        return date.isAfter(YearMonth.of(expiryYear, expiryMonth).atEndOfMonth());
    }

    public boolean isUsableOn(LocalDate date) {
        return status == CardStatus.ACTIVE && !isExpiredOn(date);
    }

    /**
     * Throws unless the card can be used for a payment on the given date.
     * Called by the payment flow in Phase 1.4.
     */
    public void assertUsableOn(LocalDate date) {
        if (status != CardStatus.ACTIVE) {
            throw new BusinessRuleViolationException(ErrorCode.CARD_NOT_USABLE,
                    "card %s is %s and cannot be used".formatted(cardId, status));
        }
        if (isExpiredOn(date)) {
            throw new BusinessRuleViolationException(ErrorCode.CARD_NOT_USABLE,
                    "card %s expired at the end of %02d/%d".formatted(cardId, expiryMonth, expiryYear));
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void block(Instant now) {
        transitionTo(CardStatus.BLOCKED, now);
    }

    public void unblock(Instant now) {
        transitionTo(CardStatus.ACTIVE, now);
    }

    public void cancel(Instant now) {
        transitionTo(CardStatus.CANCELLED, now);
    }

    /** Applied by the expiry sweep, not by a user request. */
    public void markExpired(Instant now) {
        this.status = CardStatus.EXPIRED;
        this.updatedAt = now;
    }

    private void transitionTo(CardStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessRuleViolationException(ErrorCode.INVALID_STATE_TRANSITION,
                    "card %s cannot move from %s to %s".formatted(cardId, status, target));
        }
        this.status = target;
        this.updatedAt = now;
    }

    // ------------------------------------------------------------------

    @Override
    public UUID getId() {
        return cardId;
    }

    public UUID cardId() {
        return cardId;
    }

    public UUID accountId() {
        return accountId;
    }

    public String cardToken() {
        return cardToken;
    }

    public String panMasked() {
        return panMasked;
    }

    public CardScheme cardScheme() {
        return cardScheme;
    }

    public int expiryMonth() {
        return expiryMonth;
    }

    public int expiryYear() {
        return expiryYear;
    }

    public CardStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
