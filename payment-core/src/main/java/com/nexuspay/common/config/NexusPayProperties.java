package com.nexuspay.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;

/**
 * Every business rule marked [DECISION] in {@code docs/business-requirements.md}.
 * <p>
 * These live in configuration rather than in code because they are Sagar's calls
 * to change, and because a fee rate hardcoded in a service is a fee rate nobody
 * can find during an incident.
 */
@ConfigurationProperties(prefix = "nexuspay")
public record NexusPayProperties(
        Clearing clearing,
        Authorization authorization,
        Fees fees,
        Risk risk,
        Tokenization tokenization,
        Idempotency idempotency) {

    /** Clearing cut-off, evaluated in Asia/Tokyo. */
    public record Clearing(LocalTime cutOff) {
    }

    /** How long an issuer's guarantee stands before the hold is released. */
    public record Authorization(Duration retailValidity, Duration travelValidity) {
    }

    /**
     * The fee schedule. Rates are fractions, not percentages: 3.3% is 0.033.
     */
    public record Fees(
            BigDecimal merchantDiscountRate,
            BigDecimal interchangeRate,
            BigDecimal schemeFeeRate) {

        public Fees {
            requireFraction(merchantDiscountRate, "merchantDiscountRate");
            requireFraction(interchangeRate, "interchangeRate");
            requireFraction(schemeFeeRate, "schemeFeeRate");

            // The acquirer's margin is the residual, so it would go negative if
            // the two named components ever exceeded the total the merchant
            // pays. Failing at startup beats discovering it in a settlement run.
            BigDecimal named = interchangeRate.add(schemeFeeRate);
            if (named.compareTo(merchantDiscountRate) > 0) {
                throw new IllegalArgumentException(
                        "interchange (%s) + scheme fee (%s) exceeds the merchant discount rate (%s), which would make the acquirer margin negative"
                                .formatted(interchangeRate, schemeFeeRate, merchantDiscountRate));
            }
        }

        private static void requireFraction(BigDecimal rate, String name) {
            if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(
                        "%s must be a fraction between 0 and 1, but was %s".formatted(name, rate));
            }
        }
    }

    /**
     * Key used to derive card tokens. Must come from the environment in any
     * real deployment — the default exists so tests and local runs work, and
     * changing it invalidates every token already stored.
     */
    public record Tokenization(String secret) {

        public Tokenization {
            if (secret == null || secret.isBlank()) {
                throw new IllegalArgumentException("nexuspay.tokenization.secret must not be blank");
            }
        }
    }

    /**
     * How long an Idempotency-Key is honoured. After this a retry counts as a
     * new request — the lesser evil, since holding keys forever grows the table
     * without bound.
     */
    public record Idempotency(java.time.Duration retention) {
    }

    /** Risk thresholds. Detail in Phase 3.1. */
    public record Risk(
            BigDecimal singleTransactionCeiling,
            int velocityCountPerHour,
            BigDecimal velocityValuePerDay) {
    }
}
