package com.nexuspay.common.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Nested
    @DisplayName("minor units")
    class MinorUnits {

        @Test
        @DisplayName("JPY rejects a fractional amount")
        void jpyRejectsFractions() {
            assertThatThrownBy(() -> Money.of("5000.50", "JPY"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("JPY has 0 minor unit(s)");
        }

        @Test
        @DisplayName("JPY accepts trailing zeros, because 5000.00 really is 5000")
        void jpyAcceptsTrailingZeros() {
            assertThat(Money.of("5000.00", "JPY").amount()).isEqualByComparingTo("5000");
        }

        @Test
        @DisplayName("USD allows two decimal places but not four")
        void usdAllowsTwoPlaces() {
            assertThat(Money.of("50.25", "USD").amount()).isEqualByComparingTo("50.25");

            assertThatThrownBy(() -> Money.of("50.2545", "USD"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("USD has 2 minor unit(s)");
        }
    }

    @Nested
    @DisplayName("currency safety")
    class CurrencySafety {

        @Test
        @DisplayName("adding different currencies is refused")
        void cannotAddDifferentCurrencies() {
            assertThatThrownBy(() -> Money.of(5000, "JPY").plus(Money.of("50.00", "USD")))
                    .isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("cannot combine JPY with USD");
        }

        @Test
        @DisplayName("comparing different currencies is refused")
        void cannotCompareDifferentCurrencies() {
            assertThatThrownBy(() -> Money.of(5000, "JPY").isGreaterThan(Money.of("50.00", "USD")))
                    .isInstanceOf(CurrencyMismatchException.class);
        }

        @Test
        @DisplayName("same-currency arithmetic works")
        void sameCurrencyArithmetic() {
            assertThat(Money.of(5000, "JPY").plus(Money.of(165, "JPY")))
                    .isEqualTo(Money.of(5165, "JPY"));
            assertThat(Money.of(5000, "JPY").minus(Money.of(165, "JPY")))
                    .isEqualTo(Money.of(4835, "JPY"));
        }
    }

    @Nested
    @DisplayName("the fee schedule")
    class Fees {

        private static final BigDecimal MDR = new BigDecimal("0.0330");
        private static final BigDecimal INTERCHANGE = new BigDecimal("0.0200");
        private static final BigDecimal SCHEME = new BigDecimal("0.0030");
        private static final BigDecimal MARGIN_RATE = new BigDecimal("0.0100");

        @Test
        @DisplayName("the ¥5,000 dinner splits exactly as documented")
        void referenceExample() {
            Money gross = Money.of(5000, "JPY");

            Money mdr = gross.multipliedBy(MDR);
            Money interchange = gross.multipliedBy(INTERCHANGE);
            Money scheme = gross.multipliedBy(SCHEME);
            Money acquirerMargin = mdr.minus(interchange).minus(scheme);

            assertThat(mdr).isEqualTo(Money.of(165, "JPY"));
            assertThat(interchange).isEqualTo(Money.of(100, "JPY"));
            assertThat(scheme).isEqualTo(Money.of(15, "JPY"));
            assertThat(acquirerMargin).isEqualTo(Money.of(50, "JPY"));
            assertThat(gross.minus(mdr)).isEqualTo(Money.of(4835, "JPY"));
        }

        /**
         * The reason business-requirements.md section 7 insists the acquirer
         * margin is a residual. ¥555 is an amount where four independent
         * HALF_UP roundings do not reconcile.
         */
        @Test
        @DisplayName("computing every fee component independently creates money out of nothing")
        void independentRoundingLeaksMoney() {
            Money gross = Money.of(555, "JPY");

            Money mdr = gross.multipliedBy(MDR);                 // 18.315 -> 18
            Money interchange = gross.multipliedBy(INTERCHANGE); // 11.100 -> 11
            Money scheme = gross.multipliedBy(SCHEME);           //  1.665 ->  2
            Money marginIfComputedDirectly = gross.multipliedBy(MARGIN_RATE); // 5.55 -> 6

            Money componentsSummed = interchange.plus(scheme).plus(marginIfComputedDirectly);

            assertThat(mdr).isEqualTo(Money.of(18, "JPY"));
            assertThat(componentsSummed)
                    .as("four independent roundings overshoot the total the merchant actually paid")
                    .isEqualTo(Money.of(19, "JPY"));
            assertThat(componentsSummed.minus(mdr))
                    .as("one yen conjured from nothing — per transaction")
                    .isEqualTo(Money.of(1, "JPY"));
        }

        @Test
        @DisplayName("taking the margin as the residual always reconciles")
        void residualAlwaysReconciles() {
            for (long yen = 1; yen <= 2000; yen++) {
                Money gross = Money.of(yen, "JPY");

                Money mdr = gross.multipliedBy(MDR);
                Money interchange = gross.multipliedBy(INTERCHANGE);
                Money scheme = gross.multipliedBy(SCHEME);
                Money residual = mdr.minus(interchange).minus(scheme);

                assertThat(interchange.plus(scheme).plus(residual))
                        .as("components must sum exactly to the MDR for ¥%d", yen)
                        .isEqualTo(mdr);
            }
        }
    }

    @Test
    @DisplayName("rounding is HALF_UP")
    void roundingIsHalfUp() {
        // 100 * 0.005 = 0.5 -> 1, not 0
        assertThat(Money.of(100, "JPY").multipliedBy(new BigDecimal("0.005")))
                .isEqualTo(Money.of(1, "JPY"));
    }

    @Test
    @DisplayName("toString names the currency, so a log line is never ambiguous")
    void readableToString() {
        assertThat(Money.of(5000, "JPY")).hasToString("5000 JPY");
        assertThat(Money.of("50.25", "USD")).hasToString("50.25 USD");
    }
}
