# Data Contracts

The agreement between **payment-core** (producer) and **data-hub** (consumer).

This document is the single source of truth for the files crossing between them.
The Phase 2 synthetic generator and the Phase 3 real exports must both be
buildable from this document *alone*. Changing anything here means changing the
contract tests in the same commit.

**Schema version: 1**

---

## 1. Transport

| Property | Value |
|---|---|
| Format | CSV, RFC 4180 |
| Encoding | UTF-8, no BOM |
| Header row | Required, exact column names below, exact order |
| Line ending | `\n` |
| Quoting | Only where needed (embedded comma, quote or newline); `"` escaped as `""` |
| Compression | None in v1 |
| Landing zone | `data-hub/landing/{dataset}/` locally; `s3://nexuspay-landing/{dataset}/` in Phase 5 |

**Why CSV rather than Parquet or Avro:** the file is the interface to systems we
do not control — in the real world, clearing and settlement files arrive as flat
text. CSV also makes the Phase 2 failure modes (corrupt rows, truncated files,
drifting schemas) genuinely reproducible, which is the point of the test suite.
Parquet would hide exactly the class of bug we want to catch.

### File naming

```
{dataset}_{business_date}_{batch_id}_v{schema_version}.csv
```

Example: `payments_2026-09-14_B20260914001_v1.csv`

| Part | Format | Notes |
|---|---|---|
| `dataset` | `payments` \| `ledger_entries` \| `clearing` \| `settlement` | |
| `business_date` | `YYYY-MM-DD` | Asia/Tokyo business date, **not** UTC |
| `batch_id` | `B` + `YYYYMMDD` + 3-digit sequence | Unique forever; never reused |
| `schema_version` | integer | Bumped on any breaking column change |

**A file name is a fact, not a hint.** Bronze parses `batch_id` and
`schema_version` from it and fails loudly on a mismatch with the file contents.

---

## 2. Conventions applying to every dataset

| Concern | Rule |
|---|---|
| **Money** | Decimal string, `.` separator, no thousands separator, no currency symbol. JPY values are integral (`5000`, never `5000.00`). Max precision `DECIMAL(18,4)`. |
| **Currency** | ISO 4217 uppercase, always beside its amount. Never implied. |
| **Timestamps** | ISO 8601 UTC with `Z`: `2026-09-12T10:42:07Z`. Second precision. |
| **Business dates** | `YYYY-MM-DD`, computed in Asia/Tokyo. |
| **IDs** | UUID v7 as lowercase hyphenated string, except `batch_id`. UUID v7 sorts by creation time, which keeps Delta file pruning effective. |
| **Booleans** | `true` / `false`, lowercase. |
| **Nulls** | Empty field. Never the literal `NULL`, `\N` or `-`. |
| **PAN** | Masked, first 6 + last 4: `411111******1111`. A raw PAN in any file is a contract breach and a security incident. |
| **Enums** | Uppercase, exactly as in `transaction-lifecycle.md`. |

### Lineage columns — on every row of every dataset

| Column | Type | Meaning |
|---|---|---|
| `correlation_id` | uuid | Follows one payment across API, DB, Kafka, files and the data hub |
| `batch_id` | string | The batch that produced this file |
| `schema_version` | int | Matches the file name |

Bronze additionally stamps `source_file`, `ingested_at` and `_rescued_data` on
ingestion. Producers never send those three.

---

## 3. Dataset: `payments`

One row per payment, reflecting its state at export time.

| # | Column | Type | Null? | Notes |
|---|---|---|---|---|
| 1 | `payment_id` | uuid | no | Primary key |
| 2 | `correlation_id` | uuid | no | |
| 3 | `merchant_id` | uuid | no | |
| 4 | `terminal_id` | uuid | yes | Null for e-commerce |
| 5 | `card_token` | string | no | Tokenised card reference |
| 6 | `pan_masked` | string | no | `411111******1111` |
| 7 | `card_scheme` | enum | no | `VISA` \| `MASTERCARD` \| `JCB` \| `AMEX` |
| 8 | `amount` | decimal | no | Requested amount |
| 9 | `currency` | string(3) | no | |
| 10 | `authorized_amount` | decimal | yes | Null before authorization |
| 11 | `captured_amount` | decimal | yes | Null before capture |
| 12 | `refunded_amount` | decimal | no | Cumulative; `0` if none |
| 13 | `status` | enum | no | From `transaction-lifecycle.md` |
| 14 | `mcc` | string(4) | no | Merchant category code |
| 15 | `auth_code` | string(6) | yes | Issuer's approval code |
| 16 | `risk_decision` | enum | yes | `APPROVE` \| `REVIEW` \| `DECLINE` |
| 17 | `risk_score` | int | yes | 0–100 |
| 18 | `decline_reason` | string | yes | Required when `status = DECLINED` |
| 19 | `created_at` | timestamp | no | UTC |
| 20 | `authorized_at` | timestamp | yes | UTC |
| 21 | `captured_at` | timestamp | yes | UTC |
| 22 | `business_date` | date | no | Asia/Tokyo |
| 23 | `batch_id` | string | no | |
| 24 | `schema_version` | int | no | |

### Invariants the data hub must assert

- `authorized_amount ≤ amount`
- `captured_amount ≤ authorized_amount`
- `refunded_amount ≤ captured_amount`
- `status = DECLINED` ⟹ `decline_reason` is present
- `status ∈ {CAPTURED, CLEARED, SETTLED}` ⟹ `captured_at` is present
- `payment_id` is unique within a batch

---

## 4. Dataset: `ledger_entries`

One row per ledger entry. Entries belong to journals; **a journal always
balances**.

| # | Column | Type | Null? | Notes |
|---|---|---|---|---|
| 1 | `entry_id` | uuid | no | Primary key |
| 2 | `journal_id` | uuid | no | Groups the entries that balance together |
| 3 | `correlation_id` | uuid | no | |
| 4 | `payment_id` | uuid | yes | Null for fee-only or settlement journals |
| 5 | `account_code` | string | no | See chart of accounts below |
| 6 | `account_type` | enum | no | `ASSET` \| `LIABILITY` \| `REVENUE` \| `EXPENSE` |
| 7 | `direction` | enum | no | `DEBIT` \| `CREDIT` |
| 8 | `amount` | decimal | no | Always positive; `direction` carries the sign |
| 9 | `currency` | string(3) | no | |
| 10 | `journal_type` | enum | no | `CAPTURE` \| `CLEARING` \| `SETTLEMENT` \| `REFUND` \| `REVERSAL` \| `FEE` |
| 11 | `posted_at` | timestamp | no | UTC |
| 12 | `business_date` | date | no | Asia/Tokyo |
| 13 | `batch_id` | string | no | |
| 14 | `schema_version` | int | no | |

### Invariants — the heart of the test suite

- For every `journal_id`: **Σ(DEBIT amount) = Σ(CREDIT amount)**, exactly, per currency.
- `amount > 0` always. Negative amounts are a contract breach; direction encodes sign.
- Entries are **append-only**. A given `entry_id` never appears twice with
  different values across any two exports.
- A correction appears as *new* entries with `journal_type = REVERSAL`, never as a
  changed row.

### Chart of accounts (v1)

| Code | Type | Meaning |
|---|---|---|
| `DUE_FROM_ISSUER` | ASSET | Owed to NexusPay by an issuer |
| `DUE_TO_ACQUIRER` | LIABILITY | Owed by NexusPay to an acquirer |
| `SCHEME_FEE_REVENUE` | REVENUE | NexusPay's fee income |
| `SETTLEMENT_CLEARING` | ASSET | Cash in flight during settlement |
| `INTERCHANGE_PAYABLE` | LIABILITY | Interchange owed onward to an issuer |

Worked example — one ¥5,000 dinner at clearing:

| `journal_id` | `account_code` | `direction` | `amount` |
|---|---|---|---|
| `j-1` | `DUE_FROM_ISSUER` | DEBIT | 4900 |
| `j-1` | `DUE_TO_ACQUIRER` | CREDIT | 4885 |
| `j-1` | `SCHEME_FEE_REVENUE` | CREDIT | 15 |

Debits 4900 = credits 4900. ✅

---

## 5. Dataset: `clearing`

One row per transaction in a clearing batch — the fee breakdown made explicit.

| # | Column | Type | Null? | Notes |
|---|---|---|---|---|
| 1 | `clearing_record_id` | uuid | no | |
| 2 | `batch_id` | string | no | |
| 3 | `correlation_id` | uuid | no | |
| 4 | `payment_id` | uuid | no | |
| 5 | `acquirer_id` | uuid | no | |
| 6 | `issuer_id` | uuid | no | |
| 7 | `merchant_id` | uuid | no | |
| 8 | `gross_amount` | decimal | no | What the cardholder paid |
| 9 | `interchange_fee` | decimal | no | To the issuer |
| 10 | `scheme_fee` | decimal | no | To NexusPay |
| 11 | `acquirer_margin` | decimal | no | Residual |
| 12 | `net_to_merchant` | decimal | no | What the merchant receives |
| 13 | `currency` | string(3) | no | |
| 14 | `transaction_type` | enum | no | `PURCHASE` \| `REFUND` |
| 15 | `business_date` | date | no | Asia/Tokyo |
| 16 | `cleared_at` | timestamp | no | UTC |
| 17 | `schema_version` | int | no | |

### Invariants

- `net_to_merchant = gross_amount − interchange_fee − scheme_fee − acquirer_margin`,
  **exactly**, with no rounding slack.
- Every fee ≥ 0.
- `transaction_type = REFUND` ⟹ `gross_amount` is negative and the fee signs match.
- Every `payment_id` here exists in `payments` with `status ∈ {CLEARED, SETTLED}`.

---

## 6. Dataset: `settlement`

One row per participant per business date — the net position. **Far fewer rows
than `clearing`; that is the point.**

| # | Column | Type | Null? | Notes |
|---|---|---|---|---|
| 1 | `settlement_id` | uuid | no | |
| 2 | `batch_id` | string | no | |
| 3 | `participant_id` | uuid | no | An acquirer or an issuer |
| 4 | `participant_type` | enum | no | `ACQUIRER` \| `ISSUER` |
| 5 | `business_date` | date | no | Asia/Tokyo |
| 6 | `gross_debits` | decimal | no | Total owed by this participant |
| 7 | `gross_credits` | decimal | no | Total owed to this participant |
| 8 | `net_amount` | decimal | no | Signed: positive = pays, negative = receives |
| 9 | `currency` | string(3) | no | |
| 10 | `transaction_count` | int | no | Transactions behind this position |
| 11 | `status` | enum | no | `PENDING` \| `APPROVED` \| `EXECUTED` \| `FAILED` |
| 12 | `executed_at` | timestamp | yes | UTC; null until executed |
| 13 | `schema_version` | int | no | |

### Invariants

- `net_amount = gross_debits − gross_credits`.
- **Σ(`net_amount`) across all participants for one business date = 0.** Money is
  conserved: every yen one participant pays, another receives. A non-zero sum
  means money was created or lost, and the pipeline must fail loudly.
- `status = EXECUTED` ⟹ `executed_at` is present.
- One row per `(participant_id, business_date)` — a second is a duplicate
  settlement and the most dangerous bug in the system.

---

## 7. Cross-dataset reconciliation

What the Gold layer proves on every run:

| # | Check | Assertion |
|---|---|---|
| 1 | Count | Rows in source file = rows in Bronze, per `batch_id` |
| 2 | Sum | Σ`amount` identical across Bronze, Silver and Gold |
| 3 | Balance | Σ debits = Σ credits per `journal_id`, in every layer |
| 4 | Coverage | Every `CLEARED`/`SETTLED` payment has a `clearing` row |
| 5 | Conservation | Σ `settlement.net_amount` per business date = 0 |
| 6 | Fee integrity | `clearing` fee components sum exactly to `gross_amount` |
| 7 | Lineage | Every Gold row traces to a `source_file` + `batch_id` |
| 8 | Idempotency | Re-ingesting a `batch_id` changes no count and no sum |
| 9 | Masking | No column anywhere matches a raw-PAN pattern |

---

## 8. Schema evolution

| Change | Breaking? | Required action |
|---|---|---|
| Add an optional column at the end | No | Bronze absorbs it; contract test flags it for review |
| Add a required column | **Yes** | Bump `schema_version` |
| Remove or rename a column | **Yes** | Bump `schema_version` |
| Change a type or widen an enum | **Yes** | Bump `schema_version` |
| Reorder columns | **Yes** | Bump `schema_version` |

Both versions must be readable during a transition. Bronze keeps raw rows
forever, so an old batch stays reprocessable after the producer moves on.

---

## 9. Deliberate failure modes the generator must inject

Phase 2's generator produces these on demand; the pipeline must survive each and
quarantine rather than crash.

| Injection | Expected behaviour |
|---|---|
| Duplicate `payment_id` in one file | Silver dedupes by business key; Bronze keeps both |
| Negative `amount` | Quarantined with reason `NEGATIVE_AMOUNT` |
| Unknown currency code | Quarantined with reason `UNKNOWN_CURRENCY` |
| Unbalanced journal | Pipeline **fails loudly** — never quarantined silently |
| Raw PAN in `pan_masked` | Quarantined with reason `UNMASKED_PAN`; alert raised |
| Truncated final row | Quarantined with reason `MALFORMED_ROW` |
| Missing settlement line | Reconciliation check 4 fails with the missing IDs |
| Late file (after cut-off) | Ingested into the *next* business date; flagged as late |
| Re-delivered `batch_id` | Ingestion is a no-op; counts and sums unchanged |
| Extra unexpected column | Captured in `_rescued_data`; contract test flags it |
