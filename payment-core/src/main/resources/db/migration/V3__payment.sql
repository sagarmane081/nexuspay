-- V3 — Payments and the operations performed against them.
--
-- The amount relationships (captured <= authorized <= requested, refunded <=
-- captured) are enforced here as well as in the domain. The domain gives good
-- error messages; the database guarantees the invariant even if a future bug,
-- a migration script or a manual fix tries to violate it.

CREATE TABLE payment (
    payment_id         UUID          PRIMARY KEY,
    -- Follows this payment across API, DB, Kafka, export files and the data hub.
    correlation_id     UUID          NOT NULL,
    merchant_id        UUID          NOT NULL REFERENCES merchant (merchant_id),
    terminal_id        UUID          NULL     REFERENCES terminal (terminal_id),
    card_id            UUID          NOT NULL REFERENCES card (card_id),

    amount             money_amount  NOT NULL,
    currency           currency_code NOT NULL,
    authorized_amount  money_amount  NULL,
    captured_amount    money_amount  NULL,
    refunded_amount    money_amount  NOT NULL DEFAULT 0,

    status             VARCHAR(20)   NOT NULL,
    mcc                CHAR(4)       NOT NULL,
    auth_code          VARCHAR(6)    NULL,
    risk_decision      VARCHAR(10)   NULL,
    risk_score         SMALLINT      NULL,
    decline_reason     VARCHAR(200)  NULL,

    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    authorized_at      TIMESTAMPTZ   NULL,
    captured_at        TIMESTAMPTZ   NULL,
    -- Asia/Tokyo calendar date, computed against the 22:00 JST cut-off.
    -- Deliberately a plain DATE: it is a business fact, not an instant.
    business_date      DATE          NOT NULL,

    CONSTRAINT payment_status_valid CHECK (status IN (
        'RECEIVED', 'VALIDATED', 'RISK_CHECK', 'AUTHORIZED', 'CAPTURED',
        'CLEARED', 'SETTLED', 'DECLINED', 'REVERSED', 'EXPIRED',
        'REFUNDED', 'FAILED')),

    CONSTRAINT payment_amount_positive
        CHECK (amount > 0),
    CONSTRAINT payment_amount_minor_units
        CHECK (valid_minor_units(amount, currency)),

    -- You may authorize at most what was asked for.
    CONSTRAINT payment_authorized_within_amount
        CHECK (authorized_amount IS NULL OR
               (authorized_amount > 0 AND authorized_amount <= amount)),

    -- Partial capture is allowed; over-capture is not. Capturing without an
    -- authorization is impossible because the comparison is against a NULL.
    CONSTRAINT payment_captured_within_authorized
        CHECK (captured_amount IS NULL OR
               (captured_amount > 0 AND authorized_amount IS NOT NULL
                AND captured_amount <= authorized_amount)),

    -- The single most important constraint on this table: you cannot refund
    -- more than you captured, however many partial refunds are involved.
    CONSTRAINT payment_refunded_within_captured
        CHECK (refunded_amount >= 0 AND
               (refunded_amount = 0 OR
                (captured_amount IS NOT NULL AND refunded_amount <= captured_amount))),

    CONSTRAINT payment_amounts_minor_units
        CHECK (valid_minor_units(authorized_amount, currency)
           AND valid_minor_units(captured_amount, currency)
           AND valid_minor_units(refunded_amount, currency)),

    -- A decline nobody can explain is a support case nobody can close.
    CONSTRAINT payment_decline_has_reason
        CHECK (status <> 'DECLINED' OR decline_reason IS NOT NULL),

    -- If we got this far, the money facts must be present.
    CONSTRAINT payment_captured_states_have_capture
        CHECK (status NOT IN ('CAPTURED', 'CLEARED', 'SETTLED')
               OR (captured_amount IS NOT NULL AND captured_at IS NOT NULL)),

    CONSTRAINT payment_authorized_states_have_auth
        CHECK (status NOT IN ('AUTHORIZED', 'CAPTURED', 'CLEARED', 'SETTLED')
               OR (authorized_amount IS NOT NULL AND authorized_at IS NOT NULL)),

    CONSTRAINT payment_risk_decision_valid
        CHECK (risk_decision IS NULL OR risk_decision IN ('APPROVE', 'REVIEW', 'DECLINE')),
    CONSTRAINT payment_risk_score_range
        CHECK (risk_score IS NULL OR risk_score BETWEEN 0 AND 100),
    CONSTRAINT payment_mcc_numeric
        CHECK (mcc ~ '^[0-9]{4}$'),

    -- Time cannot run backwards.
    CONSTRAINT payment_authorized_after_created
        CHECK (authorized_at IS NULL OR authorized_at >= created_at),
    CONSTRAINT payment_captured_after_authorized
        CHECK (captured_at IS NULL OR
               (authorized_at IS NOT NULL AND captured_at >= authorized_at))
);

CREATE INDEX payment_correlation_idx   ON payment (correlation_id);
CREATE INDEX payment_merchant_idx      ON payment (merchant_id, business_date);
CREATE INDEX payment_card_idx          ON payment (card_id, created_at);
CREATE INDEX payment_business_date_idx ON payment (business_date, status);


CREATE TABLE payment_authorization (
    authorization_id      UUID          PRIMARY KEY,
    -- One authorization per payment in v1. See business-requirements.md:
    -- multiple captures and incremental authorizations are out of scope.
    payment_id            UUID          NOT NULL UNIQUE REFERENCES payment (payment_id),
    amount                money_amount  NOT NULL,
    currency              currency_code NOT NULL,
    approved              BOOLEAN       NOT NULL,
    auth_code             VARCHAR(6)    NULL,
    issuer_response_code  VARCHAR(4)    NOT NULL,
    -- When the issuer's guarantee lapses. 7 days retail, 30 days travel.
    expires_at            TIMESTAMPTZ   NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT authorization_amount_positive
        CHECK (amount > 0),
    CONSTRAINT authorization_amount_minor_units
        CHECK (valid_minor_units(amount, currency)),
    CONSTRAINT authorization_expiry_after_creation
        CHECK (expires_at > created_at),
    -- An approval must carry an auth code; a decline must not invent one.
    CONSTRAINT authorization_approved_has_code
        CHECK ((approved AND auth_code IS NOT NULL)
            OR (NOT approved AND auth_code IS NULL))
);

CREATE INDEX authorization_expiry_idx ON payment_authorization (expires_at) WHERE approved;


CREATE TABLE capture (
    capture_id        UUID          PRIMARY KEY,
    -- UNIQUE enforces "one capture per authorization" at the storage layer,
    -- so a duplicated capture message cannot double-charge even if the
    -- application's idempotency check is bypassed or racing.
    authorization_id  UUID          NOT NULL UNIQUE REFERENCES payment_authorization (authorization_id),
    payment_id        UUID          NOT NULL REFERENCES payment (payment_id),
    amount            money_amount  NOT NULL,
    currency          currency_code NOT NULL,
    captured_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT capture_amount_positive
        CHECK (amount > 0),
    CONSTRAINT capture_amount_minor_units
        CHECK (valid_minor_units(amount, currency))
);

CREATE INDEX capture_payment_idx ON capture (payment_id);


CREATE TABLE refund (
    refund_id    UUID          PRIMARY KEY,
    payment_id   UUID          NOT NULL REFERENCES payment (payment_id),
    amount       money_amount  NOT NULL,
    currency     currency_code NOT NULL,
    reason       VARCHAR(200)  NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT refund_amount_positive
        CHECK (amount > 0),
    CONSTRAINT refund_amount_minor_units
        CHECK (valid_minor_units(amount, currency))
);

CREATE INDEX refund_payment_idx ON refund (payment_id);


CREATE TABLE reversal (
    reversal_id       UUID          PRIMARY KEY,
    -- At most one reversal per payment: a payment is either cancelled or not.
    payment_id        UUID          NOT NULL UNIQUE REFERENCES payment (payment_id),
    authorization_id  UUID          NOT NULL REFERENCES payment_authorization (authorization_id),
    amount            money_amount  NOT NULL,
    currency          currency_code NOT NULL,
    reason            VARCHAR(200)  NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT reversal_amount_positive
        CHECK (amount > 0),
    CONSTRAINT reversal_amount_minor_units
        CHECK (valid_minor_units(amount, currency))
);
