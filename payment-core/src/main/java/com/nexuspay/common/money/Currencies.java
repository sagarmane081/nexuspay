package com.nexuspay.common.money;

import com.nexuspay.common.error.BusinessRuleViolationException;
import com.nexuspay.common.error.ErrorCode;

import java.util.Currency;

/**
 * Parses ISO 4217 codes into currencies, failing as a business error rather
 * than an {@link IllegalArgumentException}.
 * <p>
 * "ZZZ" is three uppercase letters, so it satisfies every reasonable pattern
 * check and then blows up inside {@link Currency#getInstance(String)}. Without
 * this, a typo in a request body surfaces as a 500 — a server fault reported
 * for what is plainly a client mistake.
 */
public final class Currencies {

    private Currencies() {
    }

    public static Currency parse(String code) {
        if (code == null || !code.matches("^[A-Z]{3}$")) {
            throw new BusinessRuleViolationException(ErrorCode.UNSUPPORTED_CURRENCY,
                    "currency must be a three-letter ISO 4217 code");
        }
        try {
            Currency currency = Currency.getInstance(code);
            if (currency.getDefaultFractionDigits() < 0) {
                throw new BusinessRuleViolationException(ErrorCode.UNSUPPORTED_CURRENCY,
                        "%s is not a funding currency".formatted(code));
            }
            return currency;
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolationException(ErrorCode.UNSUPPORTED_CURRENCY,
                    "%s is not a known ISO 4217 currency".formatted(code));
        }
    }
}
