"""Row-level validation rules.

Each rule is a (reason, condition) pair evaluated against the **string** columns
as they arrived in Bronze. Validating before casting is deliberate: once
``"-5000"`` has been cast it is just a number, and once ``"ZZZ"`` has been cast
it is a null — the evidence of *why* a row is wrong is gone, and a quarantine
row saying "it was null" helps nobody.

The reasons are a contract of their own. They appear in the quarantine table and
in operational dashboards, so renaming one breaks whatever is counting them.

**Conditions are callables, not Columns.** A PySpark ``Column`` is a handle into
a live JVM, not plain data: ``F.col("x")`` asserts that a SparkContext exists.
Building the rule table eagerly would make this module unimportable before a
session is started — which breaks linters, editors, and anything that imports
the package to read its constants.
"""

from __future__ import annotations

from typing import Callable

from pyspark.sql import Column, functions as F

QUARANTINE_REASON = "_quarantine_reason"
QUARANTINE_DETAIL = "_quarantine_detail"

#: Currencies this system funds in. Anything else is a contract breach, not an
#: exotic-but-valid amount.
KNOWN_CURRENCIES = ("JPY", "USD", "EUR", "INR")

PAYMENT_STATUSES = (
    "RECEIVED", "VALIDATED", "RISK_CHECK", "AUTHORIZED", "CAPTURED", "CLEARED",
    "SETTLED", "DECLINED", "REVERSED", "EXPIRED", "REFUNDED", "FAILED",
)

#: first 6 + mask + last 4, exactly as payment-core's card_pan_is_masked check.
MASKED_PAN_PATTERN = r"^[0-9]{6}\*{4,9}[0-9]{4}$"

DECIMAL_PATTERN = r"^-?[0-9]+(\.[0-9]+)?$"
TIMESTAMP_PATTERN = r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
DATE_PATTERN = r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"

Condition = Callable[[], Column]


def _malformed(column: str) -> Condition:
    """The row could not be parsed into the expected shape at all."""
    return lambda: F.col("_rescued_data").isNotNull() | F.col(column).isNull()


def _not_decimal(column: str) -> Condition:
    return lambda: F.col(column).isNotNull() & ~F.col(column).rlike(DECIMAL_PATTERN)


def _negative(column: str) -> Condition:
    return lambda: F.col(column).rlike(DECIMAL_PATTERN) & (F.col(column).cast("decimal(18,4)") < 0)


def _fractional_jpy(column: str) -> Condition:
    """JPY has zero minor units, so ¥5,000.50 is not a representable amount.

    ``5000.00`` is tolerated — the same number written wastefully — but a
    non-zero fractional digit is a real error.
    """
    return lambda: (F.col("currency") == "JPY") & F.col(column).rlike(r"\.[0-9]*[1-9]")


def _amount_gt(left: str, right: str) -> Condition:
    def condition() -> Column:
        both_present = F.col(left).rlike(DECIMAL_PATTERN) & F.col(right).rlike(DECIMAL_PATTERN)
        return both_present & (
            F.col(left).cast("decimal(18,4)") > F.col(right).cast("decimal(18,4)"))
    return condition


def _not_in(column: str, *allowed: str) -> Condition:
    return lambda: ~F.col(column).isin(*allowed)


def _missing(*columns: str) -> Condition:
    def condition() -> Column:
        checks = [F.col(name).isNull() for name in columns]
        combined = checks[0]
        for check in checks[1:]:
            combined = combined | check
        return combined
    return condition


def _blank(column: str) -> Column:
    return F.col(column).isNull() | (F.col(column) == "")


def _sum_does_not_match(parts: tuple[str, ...], total: str) -> Condition:
    def condition() -> Column:
        summed = F.col(parts[0]).cast("decimal(18,4)")
        for name in parts[1:]:
            summed = summed + F.col(name).cast("decimal(18,4)")
        return F.col(total).rlike(DECIMAL_PATTERN) & (summed != F.col(total).cast("decimal(18,4)"))
    return condition


PAYMENT_RULES: tuple[tuple[str, Condition], ...] = (
    ("MALFORMED_ROW", _malformed("payment_id")),
    ("MISSING_REQUIRED_FIELD", _missing("amount", "currency", "status")),
    ("UNMASKED_PAN", lambda: ~F.col("pan_masked").rlike(MASKED_PAN_PATTERN)),
    ("UNKNOWN_CURRENCY", _not_in("currency", *KNOWN_CURRENCIES)),
    ("MALFORMED_AMOUNT", _not_decimal("amount")),
    ("NEGATIVE_AMOUNT", _negative("amount")),
    ("INVALID_MINOR_UNITS", _fractional_jpy("amount")),
    ("UNKNOWN_STATUS", _not_in("status", *PAYMENT_STATUSES)),
    ("MISSING_DECLINE_REASON",
     lambda: (F.col("status") == "DECLINED") & _blank("decline_reason")),
    ("AUTHORIZED_EXCEEDS_REQUESTED", _amount_gt("authorized_amount", "amount")),
    ("CAPTURED_EXCEEDS_AUTHORIZED", _amount_gt("captured_amount", "authorized_amount")),
    ("REFUNDED_EXCEEDS_CAPTURED", _amount_gt("refunded_amount", "captured_amount")),
    ("MALFORMED_TIMESTAMP",
     lambda: F.col("created_at").isNotNull() & ~F.col("created_at").rlike(TIMESTAMP_PATTERN)),
    ("MALFORMED_BUSINESS_DATE",
     lambda: F.col("business_date").isNotNull() & ~F.col("business_date").rlike(DATE_PATTERN)),
)

LEDGER_ENTRY_RULES: tuple[tuple[str, Condition], ...] = (
    ("MALFORMED_ROW", _malformed("entry_id")),
    ("MISSING_REQUIRED_FIELD", _missing("journal_id", "amount", "direction")),
    ("UNKNOWN_CURRENCY", _not_in("currency", *KNOWN_CURRENCIES)),
    ("MALFORMED_AMOUNT", _not_decimal("amount")),
    # The sign lives in `direction`; an amount carrying it too is a contract
    # breach, and would make every SUM depend on which convention was used.
    ("NEGATIVE_AMOUNT", _negative("amount")),
    ("INVALID_MINOR_UNITS", _fractional_jpy("amount")),
    ("UNKNOWN_DIRECTION", _not_in("direction", "DEBIT", "CREDIT")),
)

CLEARING_RULES: tuple[tuple[str, Condition], ...] = (
    ("MALFORMED_ROW", _malformed("clearing_record_id")),
    ("UNKNOWN_CURRENCY", _not_in("currency", *KNOWN_CURRENCIES)),
    ("MALFORMED_AMOUNT", _not_decimal("gross_amount")),
    ("UNKNOWN_TRANSACTION_TYPE", _not_in("transaction_type", "PURCHASE", "REFUND")),
    # Every fee plus the merchant's share must reconstruct the gross exactly.
    # A yen of slack here is money appearing from nowhere.
    ("FEE_RECONCILIATION_FAILED",
     _sum_does_not_match(
         ("interchange_fee", "scheme_fee", "acquirer_margin", "net_to_merchant"),
         "gross_amount")),
)

SETTLEMENT_RULES: tuple[tuple[str, Condition], ...] = (
    ("MALFORMED_ROW", _malformed("settlement_id")),
    ("UNKNOWN_CURRENCY", _not_in("currency", *KNOWN_CURRENCIES)),
    ("MALFORMED_AMOUNT", _not_decimal("net_amount")),
    ("UNKNOWN_PARTICIPANT_TYPE", _not_in("participant_type", "ACQUIRER", "ISSUER", "NETWORK")),
    ("UNKNOWN_STATUS", _not_in("status", "PENDING", "APPROVED", "EXECUTED", "FAILED")),
    ("NET_DOES_NOT_RECONCILE",
     lambda: F.col("net_amount").rlike(DECIMAL_PATTERN)
     & (F.col("gross_debits").cast("decimal(18,4)") - F.col("gross_credits").cast("decimal(18,4)")
        != F.col("net_amount").cast("decimal(18,4)"))),
    ("EXECUTED_WITHOUT_TIMESTAMP",
     lambda: (F.col("status") == "EXECUTED") & _blank("executed_at")),
)

RULES: dict[str, tuple[tuple[str, Condition], ...]] = {
    "payments": PAYMENT_RULES,
    "ledger_entries": LEDGER_ENTRY_RULES,
    "clearing": CLEARING_RULES,
    "settlement": SETTLEMENT_RULES,
}


def reasons_for(dataset: str) -> list[str]:
    """The reasons this dataset can produce. Importable without Spark."""
    return [reason for reason, _ in RULES[dataset]]


def reason_column(dataset: str) -> Column:
    """First matching failure reason for a row, or null when the row is clean.

    Rules are evaluated in order and the first match wins, so the most
    fundamental problem is reported rather than a downstream symptom of it — a
    row that failed to parse is ``MALFORMED_ROW``, not ``MALFORMED_AMOUNT``.
    """
    branches = [F.when(condition(), F.lit(reason)) for reason, condition in RULES[dataset]]
    return F.coalesce(*branches)
