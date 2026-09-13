package com.nexuspay.common.money;

import com.nexuspay.common.error.DomainException;
import com.nexuspay.common.error.ErrorCode;

import java.util.Currency;

/**
 * Thrown when an operation would combine two different currencies. Always a
 * programming error rather than a user error — the API validates currency long
 * before a {@link Money} is constructed.
 */
public class CurrencyMismatchException extends DomainException {

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super(ErrorCode.CURRENCY_MISMATCH,
                "cannot combine %s with %s".formatted(expected.getCurrencyCode(), actual.getCurrencyCode()));
    }
}
