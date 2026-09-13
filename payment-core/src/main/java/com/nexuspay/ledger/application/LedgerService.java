package com.nexuspay.ledger.application;

import com.nexuspay.common.error.EntityNotFoundException;
import com.nexuspay.common.money.Money;
import com.nexuspay.ledger.domain.AccountCode;
import com.nexuspay.ledger.domain.Journal;
import com.nexuspay.ledger.domain.JournalRepository;
import com.nexuspay.ledger.domain.JournalType;
import com.nexuspay.ledger.domain.LedgerAccount;
import com.nexuspay.ledger.domain.LedgerAccountRepository;
import com.nexuspay.ledger.domain.LedgerEntry;
import com.nexuspay.ledger.domain.LedgerEntryRepository;
import com.nexuspay.ledger.domain.Posting;
import com.nexuspay.ledger.domain.UnbalancedJournalException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Posts journals to NexusPay's books.
 * <p>
 * NexusPay is the network, not a bank. Its ledger records what participants owe
 * each other, never anyone's deposits — which is why the accounts are all
 * receivables, payables and fee revenue.
 * <p>
 * <b>When things post.</b> An authorization posts nothing: a hold moves no
 * value, and the cardholder's money is still the cardholder's. The ledger begins
 * at capture, when a real obligation exists. See business-requirements.md
 * section 4 for the full reasoning and the alternative that was rejected.
 */
@Service
public class LedgerService {

    private final JournalRepository journals;
    private final LedgerEntryRepository entries;
    private final LedgerAccountRepository accounts;
    private final Clock clock;

    public LedgerService(JournalRepository journals, LedgerEntryRepository entries,
                         LedgerAccountRepository accounts, Clock clock) {
        this.journals = journals;
        this.entries = entries;
        this.accounts = accounts;
        this.clock = clock;
    }

    /**
     * Capture: the merchant has claimed the funds, so the obligation becomes
     * real. The issuer owes NexusPay; NexusPay owes the acquirer.
     * <p>
     * The gross amount is posted here because fees are a clearing-time
     * calculation — at capture nobody has computed interchange yet. Phase 4.2
     * posts a second journal that adjusts these two positions down to the net
     * figures in business-requirements.md section 7.
     */
    @Transactional
    public Journal postCapture(UUID correlationId, UUID paymentId, Money amount, LocalDate businessDate) {
        return post(JournalType.CAPTURE, correlationId, paymentId,
                "capture of payment " + paymentId, businessDate,
                List.of(
                        Posting.debit(AccountCode.DUE_FROM_ISSUER, amount),
                        Posting.credit(AccountCode.DUE_TO_ACQUIRER, amount)));
    }

    /**
     * Refund: money goes back. Both positions shrink — the issuer owes us less,
     * and we owe the acquirer less.
     * <p>
     * These are new entries, never an edit of the capture journal. The original
     * charge really happened and stays in history permanently.
     */
    @Transactional
    public Journal postRefund(UUID correlationId, UUID paymentId, Money amount, LocalDate businessDate) {
        return post(JournalType.REFUND, correlationId, paymentId,
                "refund against payment " + paymentId, businessDate,
                List.of(
                        Posting.debit(AccountCode.DUE_TO_ACQUIRER, amount),
                        Posting.credit(AccountCode.DUE_FROM_ISSUER, amount)));
    }

    /**
     * Reversal of a captured payment before settlement: the capture is undone in
     * full. Identical in shape to a refund, but recorded as a REVERSAL because
     * no cash ever moved — nothing is being sent back, the claim is simply
     * withdrawn.
     */
    @Transactional
    public Journal postReversal(UUID correlationId, UUID paymentId, Money amount, LocalDate businessDate) {
        return post(JournalType.REVERSAL, correlationId, paymentId,
                "reversal of payment " + paymentId, businessDate,
                List.of(
                        Posting.debit(AccountCode.DUE_TO_ACQUIRER, amount),
                        Posting.credit(AccountCode.DUE_FROM_ISSUER, amount)));
    }

    /**
     * Writes a journal and its entries.
     * <p>
     * The balance check here duplicates the database's deferred constraint
     * trigger on purpose. The trigger is the guarantee — it cannot be bypassed
     * by any code path. This check is for the developer: it fails at the point
     * of the mistake with both totals named, instead of as an opaque constraint
     * violation at commit, several frames away from the cause.
     */
    @Transactional
    public Journal post(JournalType type, UUID correlationId, UUID paymentId, String description,
                        LocalDate businessDate, List<Posting> postings) {
        if (postings.size() < 2) {
            throw new IllegalArgumentException("a journal needs at least two postings");
        }
        assertBalanced(postings);

        Instant now = clock.instant();
        Journal journal = journals.save(
                Journal.of(correlationId, paymentId, type, description, now, businessDate));

        for (Posting posting : postings) {
            LedgerAccount account = accounts.findByAccountCode(posting.accountCode())
                    .orElseThrow(() -> new EntityNotFoundException("LedgerAccount", posting.accountCode()));

            entries.save(LedgerEntry.of(journal.journalId(), account.ledgerAccountId(),
                    posting.direction(), posting.amount(), now));
        }

        return journal;
    }

    /**
     * Per currency, because a journal debiting ¥5,000 and crediting $5,000 is
     * not balanced in any meaningful sense.
     */
    private static void assertBalanced(List<Posting> postings) {
        Map<Currency, Money> debits = new HashMap<>();
        Map<Currency, Money> credits = new HashMap<>();

        for (Posting posting : postings) {
            Currency currency = posting.amount().currency();
            Map<Currency, Money> side = posting.isDebit() ? debits : credits;
            side.merge(currency, posting.amount(), Money::plus);
        }

        for (Currency currency : debits.keySet()) {
            Money debited = debits.get(currency);
            Money credited = credits.getOrDefault(currency, Money.zero(currency));
            if (debited.compareTo(credited) != 0) {
                throw new UnbalancedJournalException(debited, credited);
            }
        }
        for (Currency currency : credits.keySet()) {
            if (!debits.containsKey(currency)) {
                throw new UnbalancedJournalException(Money.zero(currency), credits.get(currency));
            }
        }
    }
}
