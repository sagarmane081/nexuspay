# NexusPay — Business Requirements

What NexusPay is, who uses it, and the rules money obeys inside it.

Everything marked **[DECISION]** is a business rule Claude proposed and Sagar owns.
Change any of them; the code reads these values from configuration, not from
hardcoded literals.

---

## 1. What NexusPay is

NexusPay is a **card payment network** — the switch in the middle of the
four-party model. It is *not* a bank. It holds nobody's deposits.

Its job is to:

1. Route authorization messages between acquirers and issuers.
2. Collect captured transactions into clearing batches.
3. Calculate what every participant owes and is owed.
4. Settle those positions once per business date, net.
5. Prove, afterwards, that it neither created nor lost money.

---

## 2. Actors

| Actor | Role | Do they hold money? |
|---|---|---|
| **Cardholder** | Person paying with a card issued by an issuer | Has a balance at the issuer |
| **Merchant** | Business accepting the card | Paid by its acquirer |
| **Acquirer** | The merchant's bank; owns the merchant relationship | Yes |
| **Issuer** | The cardholder's bank; approves or declines | Yes |
| **NexusPay** | The network — routes, clears, settles | No; tracks positions |
| **Settlement bank** | Where participants' settlement accounts live | Yes |

**Four-party model:** cardholder, merchant, acquirer, issuer. NexusPay is
infrastructure, not a party to the money.

---

## 3. The life of one ¥5,000 payment

A dinner in Tokyo, Friday evening. This is the canonical flow the whole system
implements.

| # | When (JST) | Event | Cardholder available | Cardholder actual | Cash between banks |
|---|---|---|---|---|---|
| 1 | Fri 19:42 | **Authorization** approved, hold placed | −¥5,000 | unchanged | No |
| 2 | Fri 23:30 | **Capture** at merchant batch close | −¥5,000 | unchanged | No |
| 3 | Sat 06:00 | **Clearing** files exchanged, fees calculated | −¥5,000 | −¥5,000 | No |
| 4 | Mon 10:00 | **Settlement** executes, net positions move | — | −¥5,000 | **Yes** |
| 5 | Mon | Merchant funded, net of fees | — | — | Merchant receives ¥4,835 |

**Cash moves exactly once, at step 4.** Steps 1–3 move only messages and
obligations. Friday's dinner settles Monday because settlement runs on business
days only.

### The message path

```
Cardholder → Merchant terminal → Acquirer → NexusPay → Issuer
                                                          │
                       approve / decline + auth code   ◄──┘
```

---

## 4. Authorization

An authorization asks the issuer: *"will you stand behind this amount?"* It is a
**guarantee, not a payment**.

| Rule | Value | |
|---|---|---|
| Authorization validity (retail) | **7 days** | **[DECISION]** |
| Authorization validity (travel, lodging, vehicle rental) | **30 days** | **[DECISION]** |
| Partial capture permitted | Yes | |
| Capture amount | Must be ≤ authorized amount | |
| Multiple captures against one authorization | **Not supported in v1** | **[DECISION]** |
| Over-capture (tips, fuel adjustment) | **Not supported in v1** | **[DECISION]** |

When an authorization expires without capture, the hold is released and the
payment moves to `EXPIRED`. No money ever moved, so nothing is reversed — the
hold simply ceases to exist.

**Merchants are expected to reverse unused authorizations rather than let them
expire.** A hotel that authorizes ¥30,000 and captures ¥18,000 should send a
reversal for the unused ¥12,000 at checkout so the cardholder's funds free up in
minutes rather than in 30 days.

### Holds are not ledger entries — **[DECISION]**

An authorization creates **no journal entry**. It is recorded in the
authorization domain only.

**Why:** a hold changes nothing anybody owns or owes. The cardholder's deposit is
a liability of the *issuer*, and a hold does not reduce it — the money is still
the cardholder's. Nothing has crossed between institutions. Posting a journal for
an event where no value moved would mean inventing two accounts purely to satisfy
double-entry, and would make "sum of the ledger" stop meaning "money that
actually exists".

**Consequence:** available balance is a *derived* figure —
`available = actual − Σ(active holds)` — not a ledger balance. The ledger begins
at capture, when a real obligation exists.

**Alternative rejected:** posting a contingent pair
(`Dr Funds on Hold / Cr Authorization Hold Reserve`) inside one institution's
books. It balances and is used in production elsewhere, but it burdens the
ledger with entries that must later be unwound, and every reconciliation query
then has to exclude them.

---

## 5. Capture, reversal, refund

| | **Reversal** | **Refund** |
|---|---|---|
| Valid when | Before settlement | After settlement |
| Cash movements | 0 — none ever happened | 2 — out, then back |
| Effect | Releases the hold / cancels the obligation | A new transaction in the opposite direction |
| Cardholder statement | Shows nothing | Shows both the charge and the credit |
| Ledger | Nothing to unwind, or a same-batch cancellation | **New journal entries** |

Rules:

- A refund may not exceed the **cumulative captured amount** minus refunds already
  made against that payment.
- A refund is **never** an `UPDATE` or `DELETE` of the original entries. The
  original charge happened and stays in history permanently.
- A reversal after settlement is invalid — it must be a refund instead.
- Partial refunds are permitted; multiple partial refunds are permitted up to the
  captured total.

---

## 6. Clearing and settlement

**Clearing** exchanges records and calculates fees. **Settlement** moves cash.

| Rule | Value | |
|---|---|---|
| Clearing cut-off | **22:00 Asia/Tokyo** | **[DECISION]** |
| Business date | The Asia/Tokyo calendar date the cut-off belongs to | |
| Settlement runs | Business days only — Mon–Fri, excluding Japanese bank holidays | **[DECISION]** |
| Settlement basis | **Net** per participant per business date | |
| Settlement currency | JPY | |

A capture at 21:50 JST and one at 22:10 JST belong to **different business
dates** — different clearing file, different settlement day.

### Net settlement

Settlement is net, not gross. For one issuer on one business date:

| | Amount |
|---|---|
| Its cardholders' purchases | ¥5,000,000 |
| Refunds flowing back to them | −¥300,000 |
| **Net — issuer pays** | **¥4,700,000** |

One transfer, not a thousand. This is the economic reason networks exist.

**Settlement for a batch must never execute twice**, even under concurrent
requests — see `CLAUDE.md` invariants and Phase 4.3.

---

## 7. Fees and interchange

Default schedule for a ¥5,000 domestic purchase:

| Slice | Rate | Amount | Paid to |
|---|---|---|---|
| Merchant discount rate (MDR) | 3.30% | ¥165 | Deducted from merchant |
| ├─ Interchange | 2.00% | ¥100 | **Issuer** |
| ├─ Scheme fee | 0.30% | ¥15 | **NexusPay** |
| └─ Acquirer margin | residual | ¥50 | Acquirer |

All three rates are **[DECISION]** values and configurable per merchant category.

The issuer takes the largest slice because it carries credit risk, funds the
transaction, and runs fraud checks. Interchange is what funds cardholder rewards.

### Rounding — **[DECISION]**

JPY has **zero minor units**, so every fee must resolve to whole yen.

- Interchange and scheme fee are calculated first, rounded **HALF_UP** to whole yen.
- **Acquirer margin is the residual**: `MDR − interchange − scheme fee`.

Computing the margin as a residual rather than as its own percentage guarantees
the components always sum *exactly* to the MDR. Rounding three independent
percentages would leak one or two yen per transaction — which across a settlement
batch becomes money created from nothing, and a reconciliation break.

### Money flow, ¥5,000 dinner

```
Cardholder pays              ¥5,000
  Issuer → NexusPay          ¥4,900   (¥5,000 less ¥100 interchange kept)
    NexusPay keeps              ¥15   (scheme fee)
    NexusPay → Acquirer      ¥4,885
      Acquirer keeps            ¥50   (margin)
      Acquirer → Merchant    ¥4,835
```

### NexusPay's ledger view

NexusPay's books track positions, not deposits. Per cleared transaction:

| Account | Dr | Cr |
|---|---|---|
| Due from Issuer | ¥4,900 | |
| Due to Acquirer | | ¥4,885 |
| Scheme fee revenue | | ¥15 |

Balances: 4,900 = 4,885 + 15. Settlement later discharges the two `Due` accounts
with real cash.

---

## 8. Money, currency and time

- **Money is `BigDecimal` in Java, `DECIMAL` in PostgreSQL and Spark.** Never
  `double` or `float`.
- Every amount is stored with an **ISO 4217 currency code**. Primary currency
  **JPY** (0 minor units). USD/EUR/INR (2 minor units) arrive with FX in Phase 8.
- **Timestamps are stored in UTC** (`timestamptz`). Business dates are computed in
  **Asia/Tokyo**. These are different things and must never be conflated.
- **Synthetic card data only.** Test PANs only; never a real card number.
- PAN is **masked as first 6 + last 4** everywhere — logs, APIs, files, data hub.
  Example: `411111******1111`.

---

## 9. Risk (detail in Phase 3.1)

Every authorization passes a rule set returning `APPROVE`, `REVIEW`, or `DECLINE`.

| Rule | Default | |
|---|---|---|
| Single-transaction ceiling | ¥1,000,000 | **[DECISION]** |
| Velocity — count | 10 authorizations per card per hour | **[DECISION]** |
| Velocity — value | ¥2,000,000 per card per day | **[DECISION]** |
| Blocked / expired / cancelled card | Always `DECLINE` | |

**Every decline stores the rule IDs that fired, the score, and a timestamp.** A
decline nobody can explain is a support case nobody can close.

---

## 10. Invariants this document must never violate

Restated from `CLAUDE.md` because every rule above is subordinate to them:

1. No duplicate financial transactions.
2. Total debits = total credits, for every journal.
3. Ledger entries are immutable — corrections are new reversal entries.
4. Settlement for a batch can never execute twice.
5. Every important action is auditable, and the audit history is immutable.
6. Every transaction is traceable end to end by a single correlation ID.
7. Failures recover safely; retries are idempotent.
