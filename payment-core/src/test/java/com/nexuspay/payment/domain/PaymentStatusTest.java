package com.nexuspay.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.nexuspay.payment.domain.PaymentStatus.AUTHORIZED;
import static com.nexuspay.payment.domain.PaymentStatus.CAPTURED;
import static com.nexuspay.payment.domain.PaymentStatus.CLEARED;
import static com.nexuspay.payment.domain.PaymentStatus.DECLINED;
import static com.nexuspay.payment.domain.PaymentStatus.EXPIRED;
import static com.nexuspay.payment.domain.PaymentStatus.FAILED;
import static com.nexuspay.payment.domain.PaymentStatus.RECEIVED;
import static com.nexuspay.payment.domain.PaymentStatus.REFUNDED;
import static com.nexuspay.payment.domain.PaymentStatus.REVERSED;
import static com.nexuspay.payment.domain.PaymentStatus.RISK_CHECK;
import static com.nexuspay.payment.domain.PaymentStatus.SETTLED;
import static com.nexuspay.payment.domain.PaymentStatus.VALIDATED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1.4's requirement: every forbidden transition is forbidden.
 * <p>
 * The expected matrix below is transcribed from
 * {@code docs/transaction-lifecycle.md} rather than derived from the
 * implementation. That is the whole point — a test generated from the code
 * under test proves only that the code equals itself. If the doc and the code
 * disagree, this fails and a human decides which one is wrong.
 */
class PaymentStatusTest {

    /** Exactly the ✅ cells of the transition table in the design document. */
    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED.put(RECEIVED, EnumSet.of(VALIDATED, FAILED));
        ALLOWED.put(VALIDATED, EnumSet.of(RISK_CHECK, DECLINED, FAILED));
        ALLOWED.put(RISK_CHECK, EnumSet.of(AUTHORIZED, DECLINED, FAILED));
        ALLOWED.put(AUTHORIZED, EnumSet.of(CAPTURED, REVERSED, EXPIRED, FAILED));
        ALLOWED.put(CAPTURED, EnumSet.of(CLEARED, REVERSED, FAILED));
        ALLOWED.put(CLEARED, EnumSet.of(SETTLED, FAILED));
        ALLOWED.put(SETTLED, EnumSet.of(REFUNDED));
        ALLOWED.put(DECLINED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED.put(REVERSED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED.put(EXPIRED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED.put(REFUNDED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED.put(FAILED, EnumSet.noneOf(PaymentStatus.class));
    }

    @Test
    @DisplayName("all 144 transitions match the documented table")
    void everyCellOfTheMatrix() {
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                boolean expected = ALLOWED.get(from).contains(to);

                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s should be %s", from, to, expected ? "allowed" : "forbidden")
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("no state may transition to itself")
    void noSelfTransitions() {
        for (PaymentStatus status : PaymentStatus.values()) {
            assertThat(status.canTransitionTo(status))
                    .as("%s -> itself", status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the money-losing shortcuts are all closed")
    void theDangerousShortcutsAreForbidden() {
        // Skips capture: bills a cardholder for goods no merchant claimed.
        assertThat(AUTHORIZED.canTransitionTo(CLEARED)).isFalse();

        // Skips clearing, where fees are calculated: would pay the merchant the
        // interchange and scheme fee as well.
        assertThat(CAPTURED.canTransitionTo(SETTLED)).isFalse();

        // Cash has moved; a reversal would assert it never did.
        assertThat(SETTLED.canTransitionTo(REVERSED)).isFalse();

        // A retry is a new payment, never a mutation of the refused one.
        assertThat(DECLINED.canTransitionTo(AUTHORIZED)).isFalse();

        // Would refund the same money twice.
        assertThat(REFUNDED.canTransitionTo(REFUNDED)).isFalse();

        // Backwards movement implies unwinding immutable ledger entries.
        assertThat(SETTLED.canTransitionTo(CAPTURED)).isFalse();
        assertThat(CLEARED.canTransitionTo(CAPTURED)).isFalse();
    }

    @Test
    @DisplayName("FAILED is reachable from anything in flight, but never from SETTLED")
    void failedReachability() {
        for (PaymentStatus inFlight : EnumSet.of(RECEIVED, VALIDATED, RISK_CHECK, AUTHORIZED, CAPTURED, CLEARED)) {
            assertThat(inFlight.canTransitionTo(FAILED))
                    .as("%s -> FAILED", inFlight)
                    .isTrue();
        }

        // Cash demonstrably moved. Hiding that behind an error state would make
        // the ledger and the payment record disagree.
        assertThat(SETTLED.canTransitionTo(FAILED)).isFalse();

        for (PaymentStatus terminal : EnumSet.of(DECLINED, REVERSED, EXPIRED, REFUNDED, FAILED)) {
            assertThat(terminal.canTransitionTo(FAILED))
                    .as("%s -> FAILED", terminal)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("terminal states are terminal")
    void terminalStatesLeadNowhere() {
        for (PaymentStatus terminal : EnumSet.of(DECLINED, REVERSED, EXPIRED, REFUNDED, FAILED)) {
            assertThat(terminal.isTerminal()).isTrue();

            for (PaymentStatus target : PaymentStatus.values()) {
                assertThat(terminal.canTransitionTo(target))
                        .as("%s -> %s", terminal, target)
                        .isFalse();
            }
        }
    }
}
