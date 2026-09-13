# Transaction Lifecycle

The payment state machine. This is the spine of `payment-core` — the domain
enforces it, and every forbidden transition throws rather than silently
no-opping.

---

## States

### Transient — work still to do

| State | Meaning | Money moved |
|---|---|---|
| `RECEIVED` | Request accepted, nothing validated yet | No |
| `VALIDATED` | Card, merchant, amount and currency all check out | No |
| `RISK_CHECK` | Risk engine is evaluating | No |
| `AUTHORIZED` | Issuer approved; hold placed | No |
| `CAPTURED` | Merchant claimed the funds | No |
| `CLEARED` | In a clearing batch; fees calculated; obligations recorded | No |

### Terminal — nothing further happens

| State | Meaning | How it got here |
|---|---|---|
| `SETTLED` | Cash moved. The happy ending | From `CLEARED` |
| `DECLINED` | Issuer or risk said no | From `RISK_CHECK` or `AUTHORIZED` attempt |
| `REVERSED` | Cancelled before settlement; no cash ever moved | From `AUTHORIZED` or `CAPTURED` |
| `EXPIRED` | Authorization aged out uncaptured; hold released | From `AUTHORIZED` |
| `REFUNDED` | Money went out and came back | From `SETTLED` |
| `FAILED` | Technical failure; not a business decision | From any transient state |

**`REFUNDED` is a status on the original payment, not a lifecycle step.** The
refund itself is a *separate* payment record travelling its own
`RECEIVED → … → SETTLED` path in the opposite direction. The original keeps its
history; it is merely marked as having been refunded.

---

## The happy path

```
RECEIVED → VALIDATED → RISK_CHECK → AUTHORIZED → CAPTURED → CLEARED → SETTLED
```

## State diagram

```
                    ┌──────────┐
                    │ RECEIVED │
                    └────┬─────┘
                         │
                    ┌────▼──────┐
             ┌──────┤ VALIDATED │
             │      └────┬──────┘
             │           │
             │      ┌────▼───────┐
             │      │ RISK_CHECK ├────────────┐
             │      └────┬───────┘            │
             │           │               ┌────▼─────┐
             │      ┌────▼───────┐       │ DECLINED │
             │      │ AUTHORIZED ├──────►│(terminal)│
             │      └────┬───────┘       └──────────┘
             │           │
             │           ├──────────────► EXPIRED   (uncaptured, aged out)
             │           ├──────────────► REVERSED  (cancelled pre-settlement)
             │           │
             │      ┌────▼─────┐
             │      │ CAPTURED ├─────────► REVERSED (cancelled pre-settlement)
             │      └────┬─────┘
             │           │
             │      ┌────▼────┐
             │      │ CLEARED │
             │      └────┬────┘
             │           │
             │      ┌────▼────┐
             │      │ SETTLED ├──────────► REFUNDED (new opposing payment)
             │      └─────────┘
             │
             └──────────────────────────► FAILED (from any transient state)
```

---

## Transition table

`✅` allowed · `❌` forbidden · `—` same state

| From \ To | VALIDATED | RISK_CHECK | AUTHORIZED | CAPTURED | CLEARED | SETTLED | DECLINED | REVERSED | EXPIRED | REFUNDED | FAILED |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **RECEIVED** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| **VALIDATED** | — | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ |
| **RISK_CHECK** | ❌ | — | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ |
| **AUTHORIZED** | ❌ | ❌ | — | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ | ✅ |
| **CAPTURED** | ❌ | ❌ | ❌ | — | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ |
| **CLEARED** | ❌ | ❌ | ❌ | ❌ | — | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ |
| **SETTLED** | ❌ | ❌ | ❌ | ❌ | ❌ | — | ❌ | ❌ | ❌ | ✅ | ❌ |
| **DECLINED** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | — | ❌ | ❌ | ❌ | ❌ |
| **REVERSED** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | — | ❌ | ❌ | ❌ |
| **EXPIRED** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | — | ❌ | ❌ |
| **REFUNDED** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | — | ❌ |
| **FAILED** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | — |

---

## Why each forbidden transition is forbidden

The allowed ones are obvious. These are the ones that cost money when a system
gets them wrong.

| Forbidden | Why it must be impossible |
|---|---|
| `RECEIVED → AUTHORIZED` | Skips validation and risk. An unvalidated card or an unknown merchant would reach the issuer. Every payment passes risk — no exceptions, no "trusted merchant" bypass. |
| `AUTHORIZED → CLEARED` | Skips capture. Clearing a payment the merchant never claimed bills the cardholder for goods nobody asserted were delivered. |
| `CAPTURED → SETTLED` | Skips clearing, which is where fees are calculated. Settling without clearing moves the *gross* amount and silently pays the merchant the interchange and scheme fee too. |
| `SETTLED → REVERSED` | Cash has already moved. A reversal claims nothing ever happened, which would be a lie in the ledger. Post-settlement corrections are refunds — new entries, never erasure. |
| `SETTLED → CAPTURED` (or any backward step) | Ledger entries are immutable. Moving backwards implies unwinding entries that must stay. |
| `EXPIRED → CAPTURED` | The whole point of expiry is that the issuer's guarantee lapsed. Capturing afterwards claims funds nobody promised. |
| `REVERSED → anything` | Terminal by definition. Re-animating a reversed payment is the classic double-charge bug. |
| `DECLINED → AUTHORIZED` | The issuer said no. Retrying is a **new payment** with a new idempotency key, not a mutation of the declined one. |
| `REFUNDED → REFUNDED` | Would refund the same money twice. Multiple *partial* refunds are permitted, but each is a separate payment record; the cumulative total is checked against the captured amount. |
| `FAILED → anything` | `FAILED` means we do not know what happened downstream. Recovery is a fresh, idempotent attempt — never a resurrection of a record in an unknown state. |
| `SETTLED → FAILED` | Cash moved. A settled payment cannot retroactively become a technical failure; that would hide real money movement behind an error state. |

---

## Rules the state machine alone cannot express

Guards enforced alongside the transition check:

1. **Capture amount ≤ authorized amount.** Partial capture is allowed; over-capture is not.
2. **One capture per authorization** in v1.
3. **Cumulative refunds ≤ captured amount.** Checked against the sum of prior refunds, inside the same transaction, to avoid a concurrent double-refund.
4. **Reversal is only valid before settlement.** After settlement the API returns a
   business error directing the caller to refund instead.
5. **Expiry is time-driven, not request-driven** — a scheduled sweep moves aged
   `AUTHORIZED` payments to `EXPIRED` using the validity window in
   `business-requirements.md`.
6. **Every transition is audited** — who, what, when, why, correlation ID — and the
   audit record is immutable.

---

## Testing expectations

Phase 1.4's tests must include:

- A parameterised test walking **every cell** of the table above: allowed
  transitions succeed, forbidden ones throw.
- Capture exceeding authorization → rejected.
- Cumulative refunds exceeding capture → rejected, including when two refund
  requests arrive **concurrently**.
- Reversal after settlement → rejected with the "use refund" business error.
- Every transition writes exactly one audit record.
