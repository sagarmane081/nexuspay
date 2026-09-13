package com.nexuspay.payment.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The payment lifecycle, exactly as specified in
 * {@code docs/transaction-lifecycle.md}.
 * <p>
 * The allowed transitions are the boring part. The forbidden ones are why this
 * class exists — each one is a way a payment system loses money:
 * <ul>
 *   <li>{@code AUTHORIZED -> CLEARED} skips capture, billing a cardholder for
 *       goods no merchant ever claimed to have delivered.</li>
 *   <li>{@code CAPTURED -> SETTLED} skips clearing, where fees are calculated,
 *       so the merchant is silently paid the interchange and scheme fee too.</li>
 *   <li>{@code SETTLED -> REVERSED} claims nothing ever happened after cash has
 *       already moved. Post-settlement corrections are refunds.</li>
 *   <li>{@code DECLINED -> AUTHORIZED} retries a refusal in place. A retry is a
 *       new payment with a new idempotency key, never a mutation of this one.</li>
 * </ul>
 */
public enum PaymentStatus {

    RECEIVED,
    VALIDATED,
    RISK_CHECK,
    AUTHORIZED,
    CAPTURED,
    CLEARED,
    SETTLED,

    DECLINED,
    REVERSED,
    EXPIRED,
    REFUNDED,
    FAILED;

    /**
     * FAILED means we do not know what happened downstream, so it is reachable
     * from any state still in flight — but never from SETTLED, where cash has
     * demonstrably moved and an error state would hide that.
     */
    private static final Set<PaymentStatus> IN_FLIGHT =
            EnumSet.of(RECEIVED, VALIDATED, RISK_CHECK, AUTHORIZED, CAPTURED, CLEARED);

    public boolean canTransitionTo(PaymentStatus target) {
        if (target == FAILED) {
            return IN_FLIGHT.contains(this);
        }
        return switch (this) {
            case RECEIVED -> target == VALIDATED;
            case VALIDATED -> target == RISK_CHECK || target == DECLINED;
            case RISK_CHECK -> target == AUTHORIZED || target == DECLINED;
            case AUTHORIZED -> target == CAPTURED || target == REVERSED || target == EXPIRED;
            case CAPTURED -> target == CLEARED || target == REVERSED;
            case CLEARED -> target == SETTLED;
            case SETTLED -> target == REFUNDED;
            // Terminal. Nothing leaves these.
            case DECLINED, REVERSED, EXPIRED, REFUNDED, FAILED -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case DECLINED, REVERSED, EXPIRED, REFUNDED, FAILED -> true;
            default -> false;
        };
    }

    /** True once the issuer's guarantee exists, whether or not it has been claimed. */
    public boolean isAuthorized() {
        return switch (this) {
            case AUTHORIZED, CAPTURED, CLEARED, SETTLED -> true;
            default -> false;
        };
    }
}
