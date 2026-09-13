package com.nexuspay.ledger.domain;

/**
 * Which side of the journal an entry sits on.
 * <p>
 * Amounts are always positive; this carries the sign. Two ways to express one
 * movement would make every SUM depend on which was used.
 */
public enum EntryDirection {
    DEBIT,
    CREDIT
}
