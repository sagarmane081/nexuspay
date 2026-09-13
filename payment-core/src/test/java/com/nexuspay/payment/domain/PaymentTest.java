package com.nexuspay.payment.domain;

import com.nexuspay.common.error.BusinessRuleViolationException;
import com.nexuspay.common.error.ErrorCode;
import com.nexuspay.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 13);

    private static Payment payment(long yen) {
        return Payment.initiate(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
                Money.of(yen, "JPY"), "5812", BUSINESS_DATE, NOW);
    }

    /** Drives a payment to AUTHORIZED the way PaymentService does. */
    private static Payment authorized(long requested, long approved) {
        Payment payment = payment(requested);
        payment.validate();
        payment.beginRiskCheck();
        payment.authorize(Money.of(approved, "JPY"), "A12345", NOW);
        return payment;
    }

    private static Payment settled(long requested, long captured) {
        Payment payment = authorized(requested, requested);
        payment.capture(Money.of(captured, "JPY"), NOW);
        payment.markCleared();
        payment.markSettled();
        return payment;
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        @DisplayName("cannot authorize more than was requested")
        void cannotOverAuthorize() {
            Payment payment = payment(5000);
            payment.validate();
            payment.beginRiskCheck();

            assertThatThrownBy(() -> payment.authorize(Money.of(6000, "JPY"), "A12345", NOW))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("cannot authorize 6000 JPY");
        }

        @Test
        @DisplayName("cannot skip validation and risk on the way to authorization")
        void cannotSkipSteps() {
            Payment payment = payment(5000);

            assertThatThrownBy(() -> payment.authorize(Money.of(5000, "JPY"), "A12345", NOW))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("cannot move from RECEIVED to AUTHORIZED");
        }

        @Test
        @DisplayName("a decline must carry a reason")
        void declineRecordsReason() {
            Payment payment = payment(5000);
            payment.validate();
            payment.decline("card is BLOCKED");

            assertThat(payment.status()).isEqualTo(PaymentStatus.DECLINED);
            assertThat(payment.declineReason()).isEqualTo("card is BLOCKED");
        }
    }

    @Nested
    @DisplayName("capture")
    class Capture {

        @Test
        @DisplayName("partial capture is allowed — the hotel case")
        void partialCapture() {
            Payment payment = authorized(30000, 30000);
            payment.capture(Money.of(18000, "JPY"), NOW);

            assertThat(payment.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(payment.capturedAmount()).isEqualTo(Money.of(18000, "JPY"));
        }

        @Test
        @DisplayName("cannot capture more than was authorized")
        void cannotOverCapture() {
            Payment payment = authorized(5000, 5000);

            assertThatThrownBy(() -> payment.capture(Money.of(5001, "JPY"), NOW))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("cannot capture 5001 JPY");
        }

        @Test
        @DisplayName("cannot capture twice")
        void cannotCaptureTwice() {
            Payment payment = authorized(5000, 5000);
            payment.capture(Money.of(5000, "JPY"), NOW);

            assertThatThrownBy(() -> payment.capture(Money.of(5000, "JPY"), NOW))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("cannot move from CAPTURED to CAPTURED");
        }

        @Test
        @DisplayName("cannot capture in a different currency")
        void currencyMustMatch() {
            Payment payment = authorized(5000, 5000);

            assertThatThrownBy(() -> payment.capture(Money.of("30.00", "USD"), NOW))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .extracting(e -> ((BusinessRuleViolationException) e).errorCode())
                    .isEqualTo(ErrorCode.CURRENCY_MISMATCH);
        }
    }

    @Nested
    @DisplayName("refund")
    class RefundRules {

        @Test
        @DisplayName("a refund cannot exceed the captured amount")
        void cannotRefundMoreThanCaptured() {
            Payment payment = settled(5000, 5000);

            assertThatThrownBy(() -> payment.refund(Money.of(5001, "JPY")))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("against a capture of 5000 JPY");
        }

        @Test
        @DisplayName("cumulative partial refunds cannot exceed the captured amount")
        void cumulativeRefundsAreCapped() {
            Payment payment = settled(5000, 5000);

            payment.refund(Money.of(3000, "JPY"));
            assertThat(payment.refundedAmount()).isEqualTo(Money.of(3000, "JPY"));

            // 3000 already returned; 2500 more would be 5500 against a 5000
            // capture. Each refund looks reasonable alone — only the running
            // total catches it.
            assertThatThrownBy(() -> payment.refund(Money.of(2500, "JPY")))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("total refunds to 5500 JPY");
        }

        @Test
        @DisplayName("a partial refund leaves the payment settled, not refunded")
        void partialRefundIsNotTerminal() {
            Payment payment = settled(5000, 5000);
            payment.refund(Money.of(2000, "JPY"));

            assertThat(payment.status()).isEqualTo(PaymentStatus.SETTLED);
            assertThat(payment.refundedAmount()).isEqualTo(Money.of(2000, "JPY"));
        }

        @Test
        @DisplayName("refunding the full captured amount makes the payment REFUNDED")
        void fullRefundIsTerminal() {
            Payment payment = settled(5000, 5000);

            payment.refund(Money.of(2000, "JPY"));
            payment.refund(Money.of(3000, "JPY"));

            assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(payment.refundedAmount()).isEqualTo(Money.of(5000, "JPY"));
        }

        @Test
        @DisplayName("a fully refunded payment cannot be refunded again")
        void cannotRefundAFullyRefundedPayment() {
            Payment payment = settled(5000, 5000);
            payment.refund(Money.of(5000, "JPY"));

            assertThatThrownBy(() -> payment.refund(Money.of(1, "JPY")))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("only a settled payment can be refunded");
        }

        @Test
        @DisplayName("refunds are capped by what was captured, not what was authorized")
        void cappedByCaptureNotAuthorization() {
            // Authorized ¥30,000, captured only ¥18,000.
            Payment payment = settled(30000, 18000);

            assertThatThrownBy(() -> payment.refund(Money.of(20000, "JPY")))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("against a capture of 18000 JPY");

            assertThatCode(() -> payment.refund(Money.of(18000, "JPY")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an unsettled payment cannot be refunded")
        void cannotRefundBeforeSettlement() {
            Payment payment = authorized(5000, 5000);
            payment.capture(Money.of(5000, "JPY"), NOW);

            assertThatThrownBy(() -> payment.refund(Money.of(5000, "JPY")))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("is CAPTURED; only a settled payment can be refunded");
        }
    }

    @Nested
    @DisplayName("reversal")
    class ReversalRules {

        @Test
        @DisplayName("an authorized payment can be reversed")
        void reverseAfterAuthorization() {
            Payment payment = authorized(5000, 5000);
            payment.reverse();

            assertThat(payment.status()).isEqualTo(PaymentStatus.REVERSED);
        }

        @Test
        @DisplayName("a captured payment can still be reversed before clearing")
        void reverseAfterCapture() {
            Payment payment = authorized(5000, 5000);
            payment.capture(Money.of(5000, "JPY"), NOW);
            payment.reverse();

            assertThat(payment.status()).isEqualTo(PaymentStatus.REVERSED);
        }

        @Test
        @DisplayName("a settled payment must be refunded, not reversed")
        void cannotReverseAfterSettlement() {
            Payment payment = settled(5000, 5000);

            assertThatThrownBy(payment::reverse)
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("must be refunded rather than reversed")
                    .extracting(e -> ((BusinessRuleViolationException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        }

        @Test
        @DisplayName("a cleared payment is already on its way to settlement")
        void cannotReverseAfterClearing() {
            Payment payment = authorized(5000, 5000);
            payment.capture(Money.of(5000, "JPY"), NOW);
            payment.markCleared();

            assertThatThrownBy(payment::reverse)
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("must be refunded rather than reversed");
        }
    }
}
