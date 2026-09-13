-- V4 — The double-entry ledger.
--
-- Two invariants are enforced here rather than trusted to application code:
--   1. Entries and journals are append-only.
--   2. Every journal balances: debits = credits, per currency.
--
-- Both are enforced by the database because "the application always does the
-- right thing" is not a guarantee — it is a hope that survives until the first
-- bug, hotfix or well-intentioned manual correction.

CREATE TABLE ledger_account (
    ledger_account_id  UUID          PRIMARY KEY,
    account_code       VARCHAR(50)   NOT NULL UNIQUE,
    account_type       VARCHAR(20)   NOT NULL,
    currency           currency_code NOT NULL,
    description        VARCHAR(200)  NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ledger_account_type_valid
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE'))
);


CREATE TABLE journal (
    journal_id      UUID         PRIMARY KEY,
    correlation_id  UUID         NOT NULL,
    payment_id      UUID         NULL REFERENCES payment (payment_id),
    journal_type    VARCHAR(20)  NOT NULL,
    description     VARCHAR(200) NOT NULL,
    posted_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    business_date   DATE         NOT NULL,

    CONSTRAINT journal_type_valid CHECK (journal_type IN (
        'CAPTURE', 'CLEARING', 'SETTLEMENT', 'REFUND', 'REVERSAL', 'FEE'))
);

CREATE INDEX journal_correlation_idx   ON journal (correlation_id);
CREATE INDEX journal_payment_idx       ON journal (payment_id);
CREATE INDEX journal_business_date_idx ON journal (business_date, journal_type);


CREATE TABLE ledger_entry (
    entry_id           UUID          PRIMARY KEY,
    journal_id         UUID          NOT NULL REFERENCES journal (journal_id),
    ledger_account_id  UUID          NOT NULL REFERENCES ledger_account (ledger_account_id),
    direction          VARCHAR(6)    NOT NULL,
    -- Always strictly positive. The sign lives in `direction`, never in the
    -- amount. Allowing negative amounts would give two ways to express the same
    -- movement, and sums would silently depend on which one was used.
    amount             money_amount  NOT NULL,
    currency           currency_code NOT NULL,
    posted_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ledger_entry_direction_valid
        CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entry_amount_positive
        CHECK (amount > 0),
    CONSTRAINT ledger_entry_amount_minor_units
        CHECK (valid_minor_units(amount, currency))
);

CREATE INDEX ledger_entry_journal_idx ON ledger_entry (journal_id);
CREATE INDEX ledger_entry_account_idx ON ledger_entry (ledger_account_id, posted_at);


-- ---------------------------------------------------------------------------
-- Invariant 1: append-only
-- ---------------------------------------------------------------------------

CREATE TRIGGER journal_is_append_only
    BEFORE UPDATE OR DELETE ON journal
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER ledger_entry_is_append_only
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();


-- ---------------------------------------------------------------------------
-- Invariant 2: every journal balances
-- ---------------------------------------------------------------------------

-- Checked per currency, because a journal that debits ¥5,000 and credits
-- $5,000 is not balanced in any meaningful sense — it is two unbalanced
-- journals wearing a trench coat.
CREATE FUNCTION assert_journal_balanced()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    imbalance RECORD;
BEGIN
    SELECT currency,
           SUM(CASE WHEN direction = 'DEBIT'  THEN amount ELSE 0 END) AS debits,
           SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END) AS credits
      INTO imbalance
      FROM ledger_entry
     WHERE journal_id = NEW.journal_id
     GROUP BY currency
    HAVING SUM(CASE WHEN direction = 'DEBIT'  THEN amount ELSE 0 END)
        <> SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END)
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'unbalanced_journal: journal % in % has debits % but credits %',
            NEW.journal_id, imbalance.currency, imbalance.debits, imbalance.credits
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$;

-- DEFERRABLE INITIALLY DEFERRED is the crux: entries are inserted one row at a
-- time, so the journal is legitimately unbalanced between the first insert and
-- the last. Checking per-statement would reject every valid journal. Checking
-- at COMMIT means the invariant holds for every transaction that succeeds, and
-- any transaction that would leave the ledger unbalanced cannot commit at all.
CREATE CONSTRAINT TRIGGER ledger_entry_journal_must_balance
    AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_journal_balanced();


-- ---------------------------------------------------------------------------
-- Chart of accounts (v1) — see docs/data-contracts.md section 4
-- ---------------------------------------------------------------------------

INSERT INTO ledger_account (ledger_account_id, account_code, account_type, currency, description) VALUES
    ('01890000-0000-7000-8000-000000000001', 'DUE_FROM_ISSUER',     'ASSET',     'JPY', 'Owed to NexusPay by an issuer'),
    ('01890000-0000-7000-8000-000000000002', 'DUE_TO_ACQUIRER',     'LIABILITY', 'JPY', 'Owed by NexusPay to an acquirer'),
    ('01890000-0000-7000-8000-000000000003', 'SCHEME_FEE_REVENUE',  'REVENUE',   'JPY', 'NexusPay fee income'),
    ('01890000-0000-7000-8000-000000000004', 'SETTLEMENT_CLEARING', 'ASSET',     'JPY', 'Cash in flight during settlement'),
    ('01890000-0000-7000-8000-000000000005', 'INTERCHANGE_PAYABLE', 'LIABILITY', 'JPY', 'Interchange owed onward to an issuer');
