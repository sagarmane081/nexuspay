-- V6 — Idempotency keys.
--
-- The problem this solves: a client sends POST /payments, the payment is
-- created, and the response is lost — a timeout, a dropped connection, a phone
-- entering a tunnel. The client cannot tell "it never happened" from "it
-- happened and I didn't hear". Its only safe move is to retry, and without
-- this table the retry charges the cardholder twice.

CREATE TABLE idempotency_key (
    idempotency_key_id  UUID          PRIMARY KEY,

    key_value           VARCHAR(255)  NOT NULL,
    -- Scoped per endpoint: the same key on POST /payments and on a refund are
    -- different operations, and letting one satisfy the other would replay the
    -- wrong response.
    endpoint            VARCHAR(100)  NOT NULL,

    -- SHA-256 of the request body, hex encoded. Lets us detect a client reusing
    -- a key for a genuinely different request — which is a client bug, and one
    -- that would otherwise silently return someone else's payment.
    request_hash        VARCHAR(64)   NOT NULL,

    status              VARCHAR(20)   NOT NULL,
    response_status     INTEGER       NULL,
    -- Sized rather than TEXT so Hibernate's schema validation has an exact
    -- length to match. These payloads are a few hundred bytes.
    response_body       VARCHAR(8000) NULL,

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ   NULL,
    -- After this, the key may be reused. A retry arriving later than the window
    -- is treated as a new request, which is the lesser evil: holding keys
    -- forever grows the table without bound.
    expires_at          TIMESTAMPTZ   NOT NULL,

    CONSTRAINT idempotency_status_valid
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),

    -- A completed key that cannot produce its response is useless: the retry it
    -- was meant to protect would have nothing to replay.
    CONSTRAINT idempotency_completed_has_response
        CHECK (status <> 'COMPLETED'
               OR (response_status IS NOT NULL AND response_body IS NOT NULL AND completed_at IS NOT NULL))
);

-- The concurrency guard, and the reason this works at all.
--
-- Two identical requests racing each other both try to insert this row. Exactly
-- one wins; the loser gets a unique-violation and therefore *knows* a sibling is
-- already in flight. Checking "does a row exist?" before inserting would not
-- work — both would look, both would see nothing, and both would proceed.
CREATE UNIQUE INDEX idempotency_key_unique ON idempotency_key (key_value, endpoint);

CREATE INDEX idempotency_expiry_idx ON idempotency_key (expires_at);
