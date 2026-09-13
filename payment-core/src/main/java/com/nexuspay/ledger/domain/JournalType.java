package com.nexuspay.ledger.domain;

/** Why a journal was posted. Matches journal_type_valid in V4__ledger.sql. */
public enum JournalType {
    CAPTURE,
    CLEARING,
    SETTLEMENT,
    REFUND,
    REVERSAL,
    FEE
}
