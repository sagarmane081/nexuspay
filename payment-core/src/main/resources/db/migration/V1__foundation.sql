-- V1 — Shared types and safety mechanisms.
--
-- Everything here exists so that a money bug cannot be introduced by a careless
-- column definition later.
--
-- An earlier revision declared PostgreSQL DOMAINs for money and currency. They
-- were removed: a domain surfaces over JDBC as Types#DISTINCT, which Hibernate's
-- schema validation cannot reconcile with BigDecimal or String, and keeping
-- `ddl-auto: validate` is worth more than the type alias. The guarantees never
-- lived in the domains anyway — they live in the CHECK constraints below and on
-- each column.

-- ---------------------------------------------------------------------------
-- Minor-unit validation
-- ---------------------------------------------------------------------------

-- JPY has zero minor units: ¥5,000.50 is not a representable amount and must
-- never reach the ledger. Currencies with 2 minor units may not carry more than
-- 2 decimal places. IMMUTABLE so it can be used inside CHECK constraints.
CREATE FUNCTION valid_minor_units(amount NUMERIC, currency TEXT)
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
