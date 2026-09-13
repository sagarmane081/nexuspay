package com.nexuspay;

import com.nexuspay.support.PostgresTestcontainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1.1 "done when": the database rejects negative amounts, missing
 * currency and invalid states — on its own, without help from the application.
 * <p>
 * These tests deliberately talk to the database directly rather than through a
 * repository. The point is to prove the constraint holds even when the
 * application layer is bypassed entirely, which is exactly what happens during
 * a manual data fix or an ORM misconfiguration.
 * <p>
 * Note the absence of {@code @Transactional}: these tests must really commit,
 * because the journal-balance constraint is deferred to commit time and would
 * never fire under an always-rolled-back test transaction.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
class SchemaConstraintsTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("01890000-0000-7000-8000-0000000000c1");
    private static final UUID ACCOUNT_ID  = UUID.fromString("01890000-0000-7000-8000-0000000000a1");
    private static final UUID CARD_ID     = UUID.fromString("01890000-0000-7000-8000-0000000000ca");
    private static final UUID MERCHANT_ID = UUID.fromString("01890000-0000-7000-8000-0000000000e1");

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 14);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void seedReferenceData() {
        tx = new TransactionTemplate(txManager);

        jdbc.update("""
                INSERT INTO customer (customer_id, full_name, email, status)
                VALUES (?, 'Test Customer', 'test.customer@example.com', 'ACTIVE')
                ON CONFLICT DO NOTHING
                """, CUSTOMER_ID);

        jdbc.update("""
                INSERT INTO account (account_id, customer_id, account_type, currency, balance, status)
                VALUES (?, ?, 'CURRENT', 'JPY', 1000000, 'ACTIVE')
                ON CONFLICT DO NOTHING
                """, ACCOUNT_ID, CUSTOMER_ID);

        jdbc.update("""
                INSERT INTO card (card_id, account_id, card_token, pan_masked, card_scheme,
                                  expiry_month, expiry_year, status)
                VALUES (?, ?, 'tok_test_card_0001', '411111******1111', 'VISA', 12, 2030, 'ACTIVE')
                ON CONFLICT DO NOTHING
                """, CARD_ID, ACCOUNT_ID);

        jdbc.update("""
                INSERT INTO merchant (merchant_id, legal_name, mcc, country, settlement_currency, status)
                VALUES (?, 'Test Ramen Shop', '5812', 'JP', 'JPY', 'ACTIVE')
                ON CONFLICT DO NOTHING
                """, MERCHANT_ID);
    }

    // ------------------------------------------------------------------
    // Amounts and currency
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("money columns")
    class Money {

        @Test
        @DisplayName("a negative payment amount is rejected")
        void negativeAmountRejected() {
            assertThatThrownBy(() -> insertPayment(new BigDecimal("-5000"), "JPY", "RECEIVED"))
                    .hasMessageContaining("payment_amount_positive");
        }

        @Test
        @DisplayName("a zero payment amount is rejected")
        void zeroAmountRejected() {
            assertThatThrownBy(() -> insertPayment(BigDecimal.ZERO, "JPY", "RECEIVED"))
                    .hasMessageContaining("payment_amount_positive");
        }

        @Test
        @DisplayName("a missing currency is rejected")
        void missingCurrencyRejected() {
            assertThatThrownBy(() -> insertPayment(new BigDecimal("5000"), null, "RECEIVED"))
                    .hasMessageContaining("currency");
        }

        @Test
        @DisplayName("a malformed currency code is rejected")
        void malformedCurrencyRejected() {
            assertThatThrownBy(() -> insertPayment(new BigDecimal("5000"), "jpy", "RECEIVED"))
                    .hasMessageContaining("payment_currency_iso");
        }

        @Test
        @DisplayName("JPY cannot carry minor units — ¥5,000.50 is not a real amount")
        void jpyFractionalAmountRejected() {
            assertThatThrownBy(() -> insertPayment(new BigDecimal("5000.50"), "JPY", "RECEIVED"))
                    .hasMessageContaining("payment_amount_minor_units");
        }

        @Test
        @DisplayName("USD may carry two minor units but not four")
        void usdRespectsTwoMinorUnits() {
            assertThatCode(() -> insertPayment(new BigDecimal("50.25"), "USD", "RECEIVED"))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> insertPayment(new BigDecimal("50.2545"), "USD", "RECEIVED"))
                    .hasMessageContaining("payment_amount_minor_units");
        }
    }

    // ------------------------------------------------------------------
    // States
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("states")
    class States {

        @Test
        @DisplayName("an unknown payment status is rejected")
        void unknownStatusRejected() {
            assertThatThrownBy(() -> insertPayment(new BigDecimal("5000"), "JPY", "ALMOST_PAID"))
                    .hasMessageContaining("payment_status_valid");
        }

        @Test
        @DisplayName("a declined payment must carry a reason")
        void declineWithoutReasonRejected() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO payment (payment_id, correlation_id, merchant_id, card_id,
                                         amount, currency, status, mcc, business_date)
                    VALUES (?, ?, ?, ?, 5000, 'JPY', 'DECLINED', '5812', ?)
                    """, UUID.randomUUID(), UUID.randomUUID(), MERCHANT_ID, CARD_ID, BUSINESS_DATE))
                    .hasMessageContaining("payment_decline_has_reason");
        }

        @Test
        @DisplayName("a captured payment must actually have a captured amount")
        void capturedStateWithoutAmountRejected() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO payment (payment_id, correlation_id, merchant_id, card_id,
                                         amount, currency, status, mcc, business_date,
                                         authorized_amount, authorized_at)
                    VALUES (?, ?, ?, ?, 5000, 'JPY', 'CAPTURED', '5812', ?, 5000, now())
                    """, UUID.randomUUID(), UUID.randomUUID(), MERCHANT_ID, CARD_ID, BUSINESS_DATE))
                    .hasMessageContaining("payment_captured_states_have_capture");
        }

        @Test
        @DisplayName("an unknown card status is rejected")
        void unknownCardStatusRejected() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO card (card_id, account_id, card_token, pan_masked, card_scheme,
                                      expiry_month, expiry_year, status)
                    VALUES (?, ?, 'tok_bad_status', '411111******2222', 'VISA', 12, 2030, 'MELTED')
                    """, UUID.randomUUID(), ACCOUNT_ID))
                    .hasMessageContaining("card_status_valid");
        }
    }

    // ------------------------------------------------------------------
    // Amount relationships
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("amount relationships")
    class AmountRelationships {

        @Test
        @DisplayName("you cannot authorize more than was requested")
        void overAuthorizationRejected() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO payment (payment_id, correlation_id, merchant_id, card_id,
                                         amount, currency, status, mcc, business_date,
                                         authorized_amount, authorized_at)
                    VALUES (?, ?, ?, ?, 5000, 'JPY', 'AUTHORIZED', '5812', ?, 6000, now())
                    """, UUID.randomUUID(), UUID.randomUUID(), MERCHANT_ID, CARD_ID, BUSINESS_DATE))
                    .hasMessageContaining("payment_authorized_within_amount");
        }

        @Test
        @DisplayName("you cannot capture more than was authorized")
        void overCaptureRejected() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO payment (payment_id, correlation_id, merchant_id, card_id,
                                         amount, currency, status, mcc, business_date,
                                         authorized_amount, authorized_at, captured_amount, captured_at)
                    VALUES (?, ?, ?, ?, 5000, 'JPY', 'CAPTURED', '5812', ?, 5000, now(), 5001, now())
                    """, UUID.randomUUID(), UUID.randomUUID(), MERCHANT_ID, CARD_ID, BUSINESS_DATE))
                    .hasMessageContaining("payment_captured_within_authorized");
        }

        @Test
        @DisplayName("partial capture is allowed")
        void partialCaptureAllowed() {
            assertThatCode(() -> jdbc.update("""
                    INSERT INTO payment (payment_id, correlation_id, merchant_id, card_id,
                                         amount, currency, status, mcc, business_date,
                                         authorized_amount, authorized_at, captured_amount, captured_at)
                    VALUES (?, ?, ?, ?, 30000, 'JPY', 'CAPTURED', '5812', ?, 30000, now(), 18000, now())
                    """, UUID.randomUUID(), UUID.randomUUID(), MERCHANT_ID, CARD_ID, BUSINESS_DATE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("you cannot refund more than was captured")
        void overRefundRejected() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO payment (payment_id, correlation_id, merchant_id, card_id,
                                         amount, currency, status, mcc, business_date,
                                         authorized_amount, authorized_at, captured_amount, captured_at,
                                         refunded_amount)
                    VALUES (?, ?, ?, ?, 5000, 'JPY', 'SETTLED', '5812', ?, 5000, now(), 5000, now(), 5001)
                    """, UUID.randomUUID(), UUID.randomUUID(), MERCHANT_ID, CARD_ID, BUSINESS_DATE))
                    .hasMessageContaining("payment_refunded_within_captured");
        }

        @Test
        @DisplayName("an authorization cannot be captured twice")
        void doubleCaptureRejected() {
            UUID paymentId = insertPayment(new BigDecimal("5000"), "JPY", "RECEIVED");
            UUID authId = UUID.randomUUID();

            jdbc.update("""
                    INSERT INTO payment_authorization (authorization_id, payment_id, amount, currency,
                                               approved, auth_code, issuer_response_code, expires_at)
                    VALUES (?, ?, 5000, 'JPY', true, 'A12345', '00', now() + interval '7 days')
                    """, authId, paymentId);

            jdbc.update("""
                    INSERT INTO capture (capture_id, authorization_id, payment_id, amount, currency)
                    VALUES (?, ?, ?, 5000, 'JPY')
                    """, UUID.randomUUID(), authId, paymentId);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO capture (capture_id, authorization_id, payment_id, amount, currency)
                    VALUES (?, ?, ?, 5000, 'JPY')
                    """, UUID.randomUUID(), authId, paymentId))
                    .hasMessageContaining("capture_authorization_id_key");
        }
    }

    // ------------------------------------------------------------------
    // Card data protection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a raw PAN cannot be stored in pan_masked")
    void rawPanRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO card (card_id, account_id, card_token, pan_masked, card_scheme,
                                  expiry_month, expiry_year, status)
                VALUES (?, ?, 'tok_raw_pan', '4111111111111111', 'VISA', 12, 2030, 'ACTIVE')
                """, UUID.randomUUID(), ACCOUNT_ID))
                .hasMessageContaining("card_pan_is_masked");
    }

    // ------------------------------------------------------------------
    // Ledger invariants
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("ledger")
    class Ledger {

        @Test
        @DisplayName("a balanced journal commits — the ¥5,000 clearing example")
        void balancedJournalCommits() {
            UUID journalId = UUID.randomUUID();

            assertThatCode(() -> tx.executeWithoutResult(status -> {
                insertJournal(journalId);
                insertEntry(journalId, "DUE_FROM_ISSUER", "DEBIT", new BigDecimal("4900"));
                insertEntry(journalId, "DUE_TO_ACQUIRER", "CREDIT", new BigDecimal("4885"));
                insertEntry(journalId, "SCHEME_FEE_REVENUE", "CREDIT", new BigDecimal("15"));
            })).doesNotThrowAnyException();

            Integer entries = jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entry WHERE journal_id = ?", Integer.class, journalId);
            assertThat(entries).isEqualTo(3);
        }

        @Test
        @DisplayName("an unbalanced journal cannot commit")
        void unbalancedJournalRejected() {
            UUID journalId = UUID.randomUUID();

            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                insertJournal(journalId);
                insertEntry(journalId, "DUE_FROM_ISSUER", "DEBIT", new BigDecimal("4900"));
                insertEntry(journalId, "DUE_TO_ACQUIRER", "CREDIT", new BigDecimal("4000"));
            })).hasMessageContaining("unbalanced_journal");

            Integer entries = jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entry WHERE journal_id = ?", Integer.class, journalId);
            assertThat(entries)
                    .as("the whole transaction must roll back, leaving no partial journal")
                    .isZero();
        }

        @Test
        @DisplayName("a negative ledger amount is rejected — direction carries the sign")
        void negativeLedgerAmountRejected() {
            UUID journalId = UUID.randomUUID();

            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                insertJournal(journalId);
                insertEntry(journalId, "DUE_FROM_ISSUER", "DEBIT", new BigDecimal("-100"));
            })).hasMessageContaining("ledger_entry_amount_positive");
        }

        @Test
        @DisplayName("ledger entries cannot be updated or deleted")
        void ledgerEntriesAreAppendOnly() {
            UUID journalId = UUID.randomUUID();
            tx.executeWithoutResult(status -> {
                insertJournal(journalId);
                insertEntry(journalId, "DUE_FROM_ISSUER", "DEBIT", new BigDecimal("4900"));
                insertEntry(journalId, "DUE_TO_ACQUIRER", "CREDIT", new BigDecimal("4900"));
            });

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE ledger_entry SET amount = 1 WHERE journal_id = ?", journalId))
                    .hasMessageContaining("append_only_violation");

            assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM ledger_entry WHERE journal_id = ?", journalId))
                    .hasMessageContaining("append_only_violation");

            assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM journal WHERE journal_id = ?", journalId))
                    .hasMessageContaining("append_only_violation");
        }
    }

    @Test
    @DisplayName("audit events cannot be updated or deleted")
    void auditEventsAreAppendOnly() {
        UUID auditId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_event (audit_event_id, correlation_id, entity_type, entity_id,
                                         action, actor_type, actor_id, reason)
                VALUES (?, ?, 'PAYMENT', ?, 'AUTHORIZED', 'SYSTEM', 'payment-core', 'issuer approved')
                """, auditId, UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_event SET action = 'NOTHING_HAPPENED' WHERE audit_event_id = ?", auditId))
                .hasMessageContaining("append_only_violation");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM audit_event WHERE audit_event_id = ?", auditId))
                .hasMessageContaining("append_only_violation");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private UUID insertPayment(BigDecimal amount, String currency, String status) {
        UUID paymentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment (payment_id, correlation_id, merchant_id, card_id,
                                     amount, currency, status, mcc, business_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, '5812', ?)
                """, paymentId, UUID.randomUUID(), MERCHANT_ID, CARD_ID,
                amount, currency, status, BUSINESS_DATE);
        return paymentId;
    }

    private void insertJournal(UUID journalId) {
        jdbc.update("""
                INSERT INTO journal (journal_id, correlation_id, journal_type, description, business_date)
                VALUES (?, ?, 'CLEARING', 'test journal', ?)
                """, journalId, UUID.randomUUID(), BUSINESS_DATE);
    }

    private void insertEntry(UUID journalId, String accountCode, String direction, BigDecimal amount) {
        UUID accountId = jdbc.queryForObject(
                "SELECT ledger_account_id FROM ledger_account WHERE account_code = ?",
                UUID.class, accountCode);

        jdbc.update("""
                INSERT INTO ledger_entry (entry_id, journal_id, ledger_account_id, direction, amount, currency)
                VALUES (?, ?, ?, ?, ?, 'JPY')
                """, UUID.randomUUID(), journalId, accountId, direction, amount);
    }
}
