package com.nexuspay.ledger.domain;

/**
 * The chart of accounts, seeded by V4__ledger.sql and documented in
 * docs/data-contracts.md section 4.
 * <p>
 * These are NexusPay's own books. NexusPay is the network, not a bank: it holds
 * nobody's deposits, so every account here records what a participant owes or is
 * owed, never anyone's balance.
 */
public final class AccountCode {

    private AccountCode() {
    }

    /** Owed to NexusPay by an issuer. */
    public static final String DUE_FROM_ISSUER = "DUE_FROM_ISSUER";

    /** Owed by NexusPay to an acquirer. */
    public static final String DUE_TO_ACQUIRER = "DUE_TO_ACQUIRER";

    /** NexusPay's fee income. */
    public static final String SCHEME_FEE_REVENUE = "SCHEME_FEE_REVENUE";

    /** Cash in flight during settlement. */
    public static final String SETTLEMENT_CLEARING = "SETTLEMENT_CLEARING";

    /** Interchange owed onward to an issuer. */
    public static final String INTERCHANGE_PAYABLE = "INTERCHANGE_PAYABLE";
}
