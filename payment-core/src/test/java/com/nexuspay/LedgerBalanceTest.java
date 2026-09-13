package com.nexuspay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexuspay.common.money.Money;
import com.nexuspay.ledger.application.LedgerService;
import com.nexuspay.ledger.domain.AccountCode;
import com.nexuspay.ledger.domain.Journal;
import com.nexuspay.ledger.domain.JournalRepository;
import com.nexuspay.ledger.domain.JournalType;
import com.nexuspay.ledger.domain.LedgerEntry;
import com.nexuspay.ledger.domain.LedgerEntryRepository;
import com.nexuspay.ledger.domain.Posting;
import com.nexuspay.ledger.domain.UnbalancedJournalException;
import com.nexuspay.payment.application.PaymentService;
import com.nexuspay.support.PostgresTestcontainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1.6: no money is created or lost.
 * <p>
 * The core requirement is the parameterized test below — debits must equal
 * credits after <em>every</em> money-moving scenario, not just the happy one.
 * Because all scenarios share one database, the global assertion also proves
 * the ledger stays balanced cumulatively, across everything the suite has done.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class LedgerBalanceTest {

    private static final Currency JPY = Currency.getInstance("JPY");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PaymentService payments;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JournalRepository journals;

    @Autowired
    private LedgerEntryRepository entries;

    @FunctionalInterface
    interface Scenario {
        void run(LedgerBalanceTest test) throws Exception;
    }

    static Stream<Arguments> everyMoneyMovingScenario() {
        return Stream.of(
                arguments("authorize only — posts nothing",
                        (Scenario) t -> t.authorizedPayment(5000)),

                arguments("authorize then full capture",
                        (Scenario) t -> t.payments.capture(t.authorizedPayment(5000), yen(5000))),

                arguments("partial capture — the hotel case",
                        (Scenario) t -> t.payments.capture(t.authorizedPayment(30000), yen(18000))),

                arguments("capture of the smallest possible amount",
                        (Scenario) t -> t.payments.capture(t.authorizedPayment(1), yen(1))),

                arguments("capture of a large amount",
                        (Scenario) t -> t.payments.capture(t.authorizedPayment(999999), yen(999999))),

                arguments("capture then reverse",
                        (Scenario) t -> {
                            UUID id = t.authorizedPayment(5000);
                            t.payments.capture(id, yen(5000));
                            t.payments.reverse(id, "terminal fault");
                        }),

                arguments("authorize then reverse — no capture, so nothing to unwind",
                        (Scenario) t -> t.payments.reverse(t.authorizedPayment(5000), "customer walked away")),

                arguments("settle then partial refund",
                        (Scenario) t -> t.payments.refund(t.settledPayment(5000, 5000), yen(2000), "partial")),

                arguments("settle then full refund",
                        (Scenario) t -> t.payments.refund(t.settledPayment(5000, 5000), yen(5000), "full")),

                arguments("settle then two partial refunds totalling the capture",
                        (Scenario) t -> {
                            UUID id = t.settledPayment(5000, 5000);
                            t.payments.refund(id, yen(3000), "first");
                            t.payments.refund(id, yen(2000), "second");
                        }),

                arguments("partial capture then refund of the captured part",
                        (Scenario) t -> t.payments.refund(t.settledPayment(30000, 18000), yen(18000), "returned")),

                arguments("refund of an odd amount that divides unevenly",
                        (Scenario) t -> t.payments.refund(t.settledPayment(555, 555), yen(333), "odd"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyMoneyMovingScenario")
    @DisplayName("debits equal credits")
    void debitsEqualCredits(String name, Scenario scenario) throws Exception {
        scenario.run(this);

        assertEveryJournalBalances();
        assertLedgerBalancesGlobally();
    }

    // ------------------------------------------------------------------
    // What each scenario actually posts
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an authorization posts no journal at all")
    void authorizationPostsNothing() throws Exception {
        UUID paymentId = authorizedPayment(5000);

        // A hold moves no value: the cardholder's money is still the
        // cardholder's, and nothing has crossed between institutions.
        // business-requirements.md section 4.
        assertThat(journals.findByPaymentId(paymentId)).isEmpty();
    }

    @Test
    @DisplayName("a capture posts one balanced journal of two entries")
    void capturePostsOneJournal() throws Exception {
        UUID paymentId = authorizedPayment(5000);
        payments.capture(paymentId, yen(5000));

        List<Journal> posted = journals.findByPaymentId(paymentId);
        assertThat(posted).hasSize(1);
        assertThat(posted.getFirst().journalType()).isEqualTo(JournalType.CAPTURE);

        List<LedgerEntry> lines = entries.findByJournalId(posted.getFirst().journalId());
        assertThat(lines).hasSize(2);
        assertThat(sumOf(lines, true)).isEqualTo(yen(5000));
        assertThat(sumOf(lines, false)).isEqualTo(yen(5000));
    }

    @Test
    @DisplayName("a refund posts new entries rather than editing the capture")
    void refundPostsNewEntries() throws Exception {
        UUID paymentId = settledPayment(5000, 5000);
        payments.refund(paymentId, yen(2000), "partial return");

        List<Journal> posted = journals.findByPaymentId(paymentId);
        assertThat(posted).hasSize(2);
        assertThat(posted).extracting(Journal::journalType)
                .containsExactlyInAnyOrder(JournalType.CAPTURE, JournalType.REFUND);

        // The original capture is untouched — the charge really happened.
        Journal capture = posted.stream()
                .filter(j -> j.journalType() == JournalType.CAPTURE).findFirst().orElseThrow();
        assertThat(sumOf(entries.findByJournalId(capture.journalId()), true)).isEqualTo(yen(5000));
    }

    @Test
    @DisplayName("reversing an uncaptured authorization posts nothing")
    void reversingAnAuthorizationPostsNothing() throws Exception {
        UUID paymentId = authorizedPayment(5000);
        payments.reverse(paymentId, "customer walked away");

        assertThat(journals.findByPaymentId(paymentId)).isEmpty();
    }

    // ------------------------------------------------------------------
    // The guard rails
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the domain refuses an unbalanced journal before the database sees it")
    void unbalancedJournalRefusedByTheDomain() {
        assertThatThrownBy(() -> ledger.post(
                JournalType.CAPTURE, UUID.randomUUID(), null, "deliberately wrong",
                LocalDate.of(2026, 9, 13),
                List.of(
                        Posting.debit(AccountCode.DUE_FROM_ISSUER, yen(5000)),
                        Posting.credit(AccountCode.DUE_TO_ACQUIRER, yen(4000)))))
                .isInstanceOf(UnbalancedJournalException.class)
                .hasMessageContaining("debits 5000 JPY but credits 4000 JPY");
    }

    @Test
    @DisplayName("a journal mixing currencies cannot balance")
    void mixedCurrencyJournalRefused() {
        assertThatThrownBy(() -> ledger.post(
                JournalType.CAPTURE, UUID.randomUUID(), null, "yen against dollars",
                LocalDate.of(2026, 9, 13),
                List.of(
                        Posting.debit(AccountCode.DUE_FROM_ISSUER, yen(5000)),
                        Posting.credit(AccountCode.DUE_TO_ACQUIRER, Money.of("50.00", "USD")))))
                .isInstanceOf(UnbalancedJournalException.class);
    }

    @Test
    @DisplayName("a posting cannot carry a negative amount")
    void negativePostingRefused() {
        assertThatThrownBy(() -> Posting.debit(AccountCode.DUE_FROM_ISSUER, Money.of(-100, "JPY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");
    }

    @Test
    @DisplayName("a journal needs at least two postings")
    void singlePostingRefused() {
        assertThatThrownBy(() -> ledger.post(
                JournalType.CAPTURE, UUID.randomUUID(), null, "one-sided",
                LocalDate.of(2026, 9, 13),
                List.of(Posting.debit(AccountCode.DUE_FROM_ISSUER, yen(5000)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two postings");
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    private void assertEveryJournalBalances() {
        List<Journal> all = journals.findAll();
        assertThat(all).as("expected at least one journal to have been posted").isNotEmpty();

        for (Journal journal : all) {
            List<LedgerEntry> lines = entries.findByJournalId(journal.journalId());

            assertThat(lines)
                    .as("journal %s (%s) must have at least two entries", journal.journalId(), journal.journalType())
                    .hasSizeGreaterThanOrEqualTo(2);

            assertThat(sumOf(lines, true))
                    .as("journal %s (%s): debits must equal credits", journal.journalId(), journal.journalType())
                    .isEqualTo(sumOf(lines, false));
        }
    }

    /**
     * The invariant that matters most: across the entire ledger, every yen
     * debited has been credited somewhere. If this ever fails, money was created
     * or destroyed.
     */
    private void assertLedgerBalancesGlobally() {
        List<LedgerEntry> all = entries.findAll();

        assertThat(sumOf(all, true))
                .as("total debits must equal total credits across the whole ledger")
                .isEqualTo(sumOf(all, false));
    }

    private static Money sumOf(List<LedgerEntry> lines, boolean debits) {
        return lines.stream()
                .filter(entry -> entry.isDebit() == debits)
                .map(LedgerEntry::amount)
                .reduce(Money.zero(JPY), Money::plus);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Money yen(long amount) {
        return Money.of(amount, "JPY");
    }

    private static final AtomicInteger PAN_SEQUENCE = new AtomicInteger(700_000);

    private UUID authorizedPayment(long yen) throws Exception {
        UUID customerId = idFrom(mvc.perform(post("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Hana Tanaka","email":"ledger+%s@example.com"}
                        """.formatted(UUID.randomUUID()))).andReturn(), "customerId");

        UUID accountId = idFrom(mvc.perform(post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerId":"%s","accountType":"CURRENT","openingBalance":9999999,"currency":"JPY"}
                        """.formatted(customerId))).andReturn(), "accountId");

        UUID cardId = idFrom(mvc.perform(post("/cards")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountId":"%s","pan":"%s","cardScheme":"VISA","expiryMonth":12,"expiryYear":2030}
                        """.formatted(accountId, "411111" + String.format("%010d", PAN_SEQUENCE.getAndIncrement()))))
                .andReturn(), "cardId");

        UUID merchantId = idFrom(mvc.perform(post("/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"legalName":"Ramen Ichiban","mcc":"5812","country":"JP","settlementCurrency":"JPY"}
                        """)).andReturn(), "merchantId");

        return idFrom(mvc.perform(post("/payments")
                        .header("Idempotency-Key", "ledger-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cardId":"%s","merchantId":"%s","amount":%d,"currency":"JPY"}
                                """.formatted(cardId, merchantId, yen)))
                .andExpect(status().isCreated())
                .andReturn(), "paymentId");
    }

    private UUID settledPayment(long authorized, long captured) throws Exception {
        UUID paymentId = authorizedPayment(authorized);
        payments.capture(paymentId, yen(captured));
        payments.markCleared(paymentId);
        payments.markSettled(paymentId);
        return paymentId;
    }

    private UUID idFrom(MvcResult result, String field) throws Exception {
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get(field).asText());
    }
}
