-- V1 — Shared types and safety mechanisms.
--
-- Everything here exists so that a money bug cannot be introduced by a careless
-- column definition later. Types are declared once; tables reuse them.

-- ---------------------------------------------------------------------------
-- Domains
-- ---------------------------------------------------------------------------

-- Every monetary column in the system is this type. Declaring it once stops
-- someone defining NUMERIC(10,2) on a new table and silently truncating.
-- 18 digits with 4 decimal places holds any realistic JPY amount while leaving
-- room for currencies with 2 minor units and for fee rates that divide unevenly.
CREATE DOMAIN money_amount AS NUMERIC(18, 4);

-- ISO 4217. Enforced by shape, not by a lookup table, so that adding a currency
-- does not require a migration.
CREATE DOMAIN currency_code AS CHAR(3)
    CHECK (VALUE ~ '^[A-Z]{3}$');

-- ---------------------------------------------------------------------------
-- Minor-unit validation
-- ---------------------------------------------------------------------------

-- JPY has zero minor units: ¥5,000.50 is not a representable amount and must
-- never reach the ledger. Currencies with 2 minor units may not carry more than
-- 2 decimal places. IMMUTABLE so it can be used inside CHECK constraints.
CREATE FUNCTION valid_minor_units(amount NUMERIC, currency CHAR(3))
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
AS $$
    SELECT CASE
        WHEN amount IS NULL OR currency IS NULL THEN true
        WHEN currency = 'JPY' THEN amount = trunc(amount)
        ELSE amount = round(amount, 2)
    END;
$$;

-- ---------------------------------------------------------------------------
-- Append-only enforcement
-- ---------------------------------------------------------------------------

-- Attached to ledger and audit tables. The application cannot opt out of this,
-- which is the point: an ORM misconfiguration, a manual psql session or a
-- well-meaning "data fix" all hit the same wall.
--
-- Corrections are made by inserting reversal entries, never by mutating history.
CREATE FUNCTION forbid_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'append_only_violation: % on % is not permitted; insert a correcting entry instead',
        TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'check_violation';
END;
$$;
