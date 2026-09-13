package com.nexuspay.ledger.domain;

import com.nexuspay.common.error.DomainException;
import com.nexuspay.common.error.ErrorCode;
import com.nexuspay.common.money.Money;

/**
 * Raised when a journal's debits do not equal its credits.
 * <p>
 * This should be unreachable in practice: the database enforces the same rule
 * with a deferred constraint trigger. It exists so the failure arrives as a
 * readable message naming both totals, rather than as an opaque constraint
 * violation at commit time, several stack frames from the code that caused it.
 */
public class UnbalancedJournalException extends DomainException {

    public UnbalancedJournalException(Money debits, Money credits) {
        super(ErrorCode.INTERNAL_ERROR,
                "journal does not balance: debits %s but credits %s".formatted(debits, credits));
    }
}
