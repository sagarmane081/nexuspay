-- V5 — Immutable audit trail.
--
-- Answers "who did what, when, and why" for every action that moves money or
-- changes a payment's fate. Append-only for the same reason the ledger is: an
-- audit log that can be edited is not evidence of anything.

CREATE TABLE audit_event (
    audit_event_id  UUID         PRIMARY KEY,
    correlation_id  UUID         NOT NULL,

    -- Polymorphic by design: one audit stream over payments, refunds,
    -- settlements and admin actions. A per-entity audit table per domain would
    -- make "show me everything that happened to this correlation ID" a union
    -- across a dozen tables.
    entity_type     VARCHAR(50)  NOT NULL,
    entity_id       UUID         NOT NULL,

    action          VARCHAR(50)  NOT NULL,
    actor_type      VARCHAR(20)  NOT NULL,
    actor_id        VARCHAR(100) NOT NULL,
    reason          VARCHAR(500) NULL,

    -- Before/after state, rule IDs that fired, issuer response — whatever the
    -- action needs to be explicable later. JSONB so the shape can vary by
    -- action without a migration per action type.
    payload         JSONB        NULL,

    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT audit_actor_type_valid
        CHECK (actor_type IN ('CUSTOMER', 'MERCHANT', 'OPERATOR', 'SYSTEM')),
    -- A SYSTEM action must say why it happened; a human's identity is the why.
    CONSTRAINT audit_system_action_has_reason
        CHECK (actor_type <> 'SYSTEM' OR reason IS NOT NULL)
);

CREATE INDEX audit_entity_idx      ON audit_event (entity_type, entity_id, occurred_at);
CREATE INDEX audit_correlation_idx ON audit_event (correlation_id, occurred_at);
CREATE INDEX audit_occurred_idx    ON audit_event (occurred_at);

CREATE TRIGGER audit_event_is_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
