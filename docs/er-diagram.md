# Entity Relationship Diagram

Schema as created by `payment-core/src/main/resources/db/migration/V1`–`V5`.

```mermaid
erDiagram
    CUSTOMER ||--o{ ACCOUNT : owns
    ACCOUNT  ||--o{ CARD    : "is drawn on by"
    MERCHANT ||--o{ TERMINAL : operates

    CARD     ||--o{ PAYMENT : "pays with"
    MERCHANT ||--o{ PAYMENT : accepts
    TERMINAL ||--o{ PAYMENT : "captured at"

    PAYMENT ||--|| PAYMENT_AUTHORIZATION : "has one"
    PAYMENT_AUTHORIZATION ||--|| CAPTURE : "claimed by one"
    PAYMENT ||--o{ REFUND   : "refunded by many"
    PAYMENT ||--|| REVERSAL : "cancelled by at most one"

    PAYMENT ||--o{ JOURNAL : "recorded in"
    JOURNAL ||--|{ LEDGER_ENTRY : "balances across"
    LEDGER_ACCOUNT ||--o{ LEDGER_ENTRY : "posted to"

    CUSTOMER {
        uuid customer_id PK
        varchar full_name
        varchar email UK
        varchar status "ACTIVE|SUSPENDED|CLOSED"
    }
    ACCOUNT {
        uuid account_id PK
        uuid customer_id FK
        varchar account_type "CURRENT|SAVINGS|CREDIT"
        char currency "ISO 4217"
        numeric balance "settled only; available is derived"
        varchar status "ACTIVE|FROZEN|CLOSED"
    }
    CARD {
        uuid card_id PK
        uuid account_id FK
        varchar card_token UK
        varchar pan_masked "first6 + last4; no raw PAN column exists"
        varchar card_scheme "VISA|MASTERCARD|JCB|AMEX"
        varchar status "ACTIVE|BLOCKED|EXPIRED|CANCELLED"
    }
    MERCHANT {
        uuid merchant_id PK
        varchar legal_name
        char mcc
        char country
        char settlement_currency
        varchar status "ACTIVE|SUSPENDED|TERMINATED"
    }
    TERMINAL {
        uuid terminal_id PK
        uuid merchant_id FK
        varchar terminal_ref UK
        varchar status "ACTIVE|INACTIVE"
    }
    PAYMENT {
        uuid payment_id PK
        uuid correlation_id "traces end to end"
        uuid merchant_id FK
        uuid terminal_id FK "null for e-commerce"
        uuid card_id FK
        numeric amount "requested"
        char currency
        numeric authorized_amount "<= amount"
        numeric captured_amount "<= authorized_amount"
        numeric refunded_amount "<= captured_amount"
        varchar status "12 states"
        date business_date "Asia/Tokyo"
    }
    PAYMENT_AUTHORIZATION {
        uuid authorization_id PK
        uuid payment_id FK,UK "one per payment in v1"
        numeric amount
        boolean approved
        varchar auth_code "present iff approved"
        timestamptz expires_at "7d retail, 30d travel"
    }
    CAPTURE {
        uuid capture_id PK
        uuid authorization_id FK,UK "UNIQUE blocks double capture"
        uuid payment_id FK
        numeric amount
        timestamptz captured_at
    }
    REFUND {
        uuid refund_id PK
        uuid payment_id FK
        numeric amount
        varchar reason
    }
    REVERSAL {
        uuid reversal_id PK
        uuid payment_id FK,UK
        uuid authorization_id FK
        numeric amount
        varchar reason
    }
    LEDGER_ACCOUNT {
        uuid ledger_account_id PK
        varchar account_code UK
        varchar account_type "ASSET|LIABILITY|REVENUE|EXPENSE"
        char currency
    }
    JOURNAL {
        uuid journal_id PK
        uuid correlation_id
        uuid payment_id FK "null for fee/settlement journals"
        varchar journal_type
        date business_date
    }
    LEDGER_ENTRY {
        uuid entry_id PK
        uuid journal_id FK
        uuid ledger_account_id FK
        varchar direction "DEBIT|CREDIT"
        numeric amount "always > 0; direction carries sign"
        char currency
    }
    AUDIT_EVENT {
        uuid audit_event_id PK
        uuid correlation_id
        varchar entity_type "polymorphic"
        uuid entity_id
        varchar action
        varchar actor_type "CUSTOMER|MERCHANT|OPERATOR|SYSTEM"
        jsonb payload
    }
```

`AUDIT_EVENT` is deliberately unlinked: it references entities polymorphically
by `(entity_type, entity_id)` so one stream covers payments, refunds,
settlements and admin actions.

## Design notes

**Why `authorization` and `capture` are separate tables rather than columns on
`payment`.** `payment` holds the *current* state; these hold what actually
happened, with their own timestamps and issuer responses. When Phase 4 adds
ISO 8583 messages, each will map to a row here. The denormalised
`authorized_amount` / `captured_amount` columns on `payment` are a deliberate
read-model convenience, and the CHECK constraints keep them honest.

**Why `capture.authorization_id` is UNIQUE.** It makes double capture
structurally impossible. Even if the idempotency layer is bypassed, or two
capture requests race, the second insert fails on the unique index rather than
double-charging the cardholder.

**Why `account` stores no available balance.** `available = balance − Σ(active
holds)` is derived at read time. Storing it would create a second source of
truth that drifts from the first — see `business-requirements.md` section 4.

**Why there is no raw PAN column.** Not "encrypted", not "restricted" — absent.
`card_pan_is_masked` rejects anything that isn't `first6 + mask + last4`.
