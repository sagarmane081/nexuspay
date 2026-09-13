package com.nexuspay.ledger.domain;

import com.nexuspay.common.money.Money;

/**
 * One line of a journal, before it is persisted.
 *
 * @param accountCode the account from the chart of accounts
 * @param direction   which side; the amount is always positive
 * @param amount      strictly positive
 */
public record Posting(String accountCode, EntryDirection direction, Money amount) {

    public Posting {
        if (accountCode == null || accountCode.isBlank()) {
            throw new IllegalArgumentException("accountCode must not be blank");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException(
                    "posting amount must be strictly positive, but was " + amount);
        }
    }

    public static Posting debit(String accountCode, Money amount) {
        return new Posting(accountCode, EntryDirection.DEBIT, amount);
    }

    public static Posting credit(String accountCode, Money amount) {
        return new Posting(accountCode, EntryDirection.CREDIT, amount);
    }

    public boolean isDebit() {
        return direction == EntryDirection.DEBIT;
    }
}
