package com.nexuspay.card.domain;

import com.nexuspay.common.error.BusinessRuleViolationException;
import com.nexuspay.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1.3's required test: a blocked or expired card cannot pay.
 * <p>
 * No Spring context and no database — the rule is pure domain logic, which is
 * exactly what the Phase 1.2 layering was for. The whole class runs in
 * milliseconds.
 */
class CardTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    private static Card activeCardExpiring(int month, int year) {
        return Card.issue(UUID.randomUUID(), "tok_test", "411111******1111",
                CardScheme.VISA, month, year, NOW);
    }

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        @DisplayName("a card is valid through the last day of its expiry month")
        void validOnFinalDayOfExpiryMonth() {
            Card card = activeCardExpiring(12, 2026);

            assertThat(card.isExpiredOn(LocalDate.of(2026, 12, 31))).isFalse();
            assertThat(card.isUsableOn(LocalDate.of(2026, 12, 31))).isTrue();
        }

        @Test
        @DisplayName("a card expires the day after its expiry month ends")
        void expiredOnFirstDayOfNextMonth() {
            Card card = activeCardExpiring(12, 2026);

            assertThat(card.isExpiredOn(LocalDate.of(2027, 1, 1))).isTrue();
            assertThat(card.isUsableOn(LocalDate.of(2027, 1, 1))).isFalse();
        }

        @Test
        @DisplayName("expiry is end-of-month, not start-of-month")
        void notExpiredOnTheFirstOfTheExpiryMonth() {
            Card card = activeCardExpiring(12, 2026);

            // Comparing against the 1st instead of the last day would kill this
            // card a month early, and thousands of good payments with it.
            assertThat(card.isUsableOn(LocalDate.of(2026, 12, 1))).isTrue();
            assertThat(card.isUsableOn(LocalDate.of(2026, 12, 15))).isTrue();
        }

        @Test
        @DisplayName("February expiry handles the short month")
        void februaryExpiry() {
            Card card = activeCardExpiring(2, 2027);

            assertThat(card.isUsableOn(LocalDate.of(2027, 2, 28))).isTrue();
            assertThat(card.isUsableOn(LocalDate.of(2027, 3, 1))).isFalse();
        }

        @Test
        @DisplayName("a leap-year February expiry is valid on the 29th")
        void leapYearExpiry() {
            Card card = activeCardExpiring(2, 2028);

            assertThat(card.isUsableOn(LocalDate.of(2028, 2, 29))).isTrue();
            assertThat(card.isUsableOn(LocalDate.of(2028, 3, 1))).isFalse();
        }

        @Test
        @DisplayName("an expired card explains itself when refused")
        void expiredCardExplainsItself() {
            Card card = activeCardExpiring(12, 2026);

            assertThatThrownBy(() -> card.assertUsableOn(LocalDate.of(2027, 1, 1)))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("expired at the end of 12/2026")
                    .extracting(e -> ((BusinessRuleViolationException) e).errorCode())
                    .isEqualTo(ErrorCode.CARD_NOT_USABLE);
        }
    }

    @Nested
    @DisplayName("status")
    class Status {

        private final LocalDate today = LocalDate.of(2026, 9, 13);

        @Test
        @DisplayName("a blocked card cannot pay even though it has not expired")
        void blockedCardCannotPay() {
            Card card = activeCardExpiring(12, 2030);
            card.block(NOW);

            assertThat(card.isExpiredOn(today)).isFalse();
            assertThat(card.isUsableOn(today)).isFalse();
            assertThatThrownBy(() -> card.assertUsableOn(today))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("is BLOCKED");
        }

        @Test
        @DisplayName("a cancelled card cannot pay")
        void cancelledCardCannotPay() {
            Card card = activeCardExpiring(12, 2030);
            card.cancel(NOW);

            assertThatThrownBy(() -> card.assertUsableOn(today))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("is CANCELLED");
        }

        @Test
        @DisplayName("an active, unexpired card can pay")
        void activeCardCanPay() {
            Card card = activeCardExpiring(12, 2030);

            assertThatCode(() -> card.assertUsableOn(today)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("lifecycle transitions")
    class Lifecycle {

        @Test
        @DisplayName("a blocked card can be unblocked")
        void blockThenUnblock() {
            Card card = activeCardExpiring(12, 2030);

            card.block(NOW);
            assertThat(card.status()).isEqualTo(CardStatus.BLOCKED);

            card.unblock(NOW);
            assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
        }

        @Test
        @DisplayName("cancellation is irreversible")
        void cancellationIsTerminal() {
            Card card = activeCardExpiring(12, 2030);
            card.cancel(NOW);

            assertThatThrownBy(() -> card.unblock(NOW))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("cannot move from CANCELLED to ACTIVE");

            assertThatThrownBy(() -> card.block(NOW))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("cannot move from CANCELLED to BLOCKED");

            assertThat(card.status()).isEqualTo(CardStatus.CANCELLED);
        }

        @Test
        @DisplayName("an already-active card cannot be activated again")
        void cannotUnblockAnActiveCard() {
            Card card = activeCardExpiring(12, 2030);

            assertThatThrownBy(() -> card.unblock(NOW))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("cannot move from ACTIVE to ACTIVE");
        }

        @Test
        @DisplayName("an expired card may only be cancelled")
        void expiredCardMayOnlyBeCancelled() {
            assertThat(CardStatus.EXPIRED.canTransitionTo(CardStatus.CANCELLED)).isTrue();
            assertThat(CardStatus.EXPIRED.canTransitionTo(CardStatus.ACTIVE)).isFalse();
            assertThat(CardStatus.EXPIRED.canTransitionTo(CardStatus.BLOCKED)).isFalse();
        }

        @Test
        @DisplayName("every transition out of CANCELLED is forbidden")
        void cancelledAllowsNothing() {
            for (CardStatus target : CardStatus.values()) {
                assertThat(CardStatus.CANCELLED.canTransitionTo(target))
                        .as("CANCELLED -> %s must be forbidden", target)
                        .isFalse();
            }
        }
    }
}
