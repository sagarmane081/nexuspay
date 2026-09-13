-- V2 — The parties: customers, their accounts and cards, merchants and terminals.
--
-- IDs are supplied by the application (UUID v7) rather than defaulted here.
-- PostgreSQL 16 has no native uuidv7(), and v7's time-ordering is what keeps
-- index locality and Delta file pruning effective downstream. One generator in
-- one place beats two that can disagree.

CREATE TABLE customer (
    customer_id   UUID         PRIMARY KEY,
    full_name     VARCHAR(200) NOT NULL,
    email         VARCHAR(320) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT customer_status_valid
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT customer_email_shape
        CHECK (email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$')
);

CREATE UNIQUE INDEX customer_email_unique ON customer (lower(email));


CREATE TABLE account (
    account_id    UUID          PRIMARY KEY,
    customer_id   UUID          NOT NULL REFERENCES customer (customer_id),
    account_type  VARCHAR(20)   NOT NULL,
    currency      currency_code NOT NULL,
    -- The settled balance only. "Available" is derived at read time as
    -- balance minus active authorization holds — see business-requirements.md
    -- section 4. Storing it would create a second source of truth that drifts.
    balance       money_amount  NOT NULL DEFAULT 0,
    status        VARCHAR(20)   NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT account_type_valid
        CHECK (account_type IN ('CURRENT', 'SAVINGS', 'CREDIT')),
    CONSTRAINT account_status_valid
        CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT account_balance_minor_units
        CHECK (valid_minor_units(balance, currency))
);

CREATE INDEX account_customer_idx ON account (customer_id);


CREATE TABLE card (
    card_id       UUID          PRIMARY KEY,
    account_id    UUID          NOT NULL REFERENCES account (account_id),
    -- There is deliberately no column for a raw PAN anywhere in this schema.
    -- You cannot leak what you never stored. The token is the only handle the
    -- system has on a card; pan_masked exists purely for human display.
    card_token    VARCHAR(64)   NOT NULL,
    pan_masked    VARCHAR(19)   NOT NULL,
    card_scheme   VARCHAR(20)   NOT NULL,
    expiry_month  SMALLINT      NOT NULL,
    expiry_year   SMALLINT      NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT card_scheme_valid
        CHECK (card_scheme IN ('VISA', 'MASTERCARD', 'JCB', 'AMEX')),
    CONSTRAINT card_status_valid
        CHECK (status IN ('ACTIVE', 'BLOCKED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT card_expiry_month_valid
        CHECK (expiry_month BETWEEN 1 AND 12),
    CONSTRAINT card_expiry_year_valid
        CHECK (expiry_year BETWEEN 2000 AND 2099),
    -- Masked form only: 6 leading digits, masking, 4 trailing digits.
    -- A full PAN written into this column fails to insert.
    CONSTRAINT card_pan_is_masked
        CHECK (pan_masked ~ '^[0-9]{6}\*{4,9}[0-9]{4}$')
);

CREATE UNIQUE INDEX card_token_unique ON card (card_token);
CREATE INDEX card_account_idx ON card (account_id);


CREATE TABLE merchant (
    merchant_id          UUID          PRIMARY KEY,
    legal_name           VARCHAR(200)  NOT NULL,
    mcc                  CHAR(4)       NOT NULL,
    country              CHAR(2)       NOT NULL,
    settlement_currency  currency_code NOT NULL,
    status               VARCHAR(20)   NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT merchant_status_valid
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'TERMINATED')),
    CONSTRAINT merchant_mcc_numeric
        CHECK (mcc ~ '^[0-9]{4}$'),
    CONSTRAINT merchant_country_iso
        CHECK (country ~ '^[A-Z]{2}$')
);


CREATE TABLE terminal (
    terminal_id   UUID         PRIMARY KEY,
    merchant_id   UUID         NOT NULL REFERENCES merchant (merchant_id),
    terminal_ref  VARCHAR(50)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT terminal_status_valid
        CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX terminal_ref_unique ON terminal (merchant_id, terminal_ref);
