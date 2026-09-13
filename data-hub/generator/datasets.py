"""Builds the four datasets defined in ``docs/data-contracts.md``.

Everything here is a pure function of :class:`GeneratorConfig`. No wall clock, no
unseeded randomness, no dictionary iteration order dependence — because the
Phase 2.1 definition of done is that the same seed produces the same files every
time, and anything reading the environment would break that quietly.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal
from typing import Any

from generator import ids, money
from generator.config import (
    CARD_SCHEMES,
    MCCS,
    SCHEMA_VERSION,
    TEST_PAN_PREFIXES,
    GeneratorConfig,
)

#: Statuses and their weights. Most payments settle; the rest exercise the
#: branches the pipeline must still handle.
STATUS_WEIGHTS = (
    ("SETTLED", 68),
    ("CLEARED", 10),
    ("CAPTURED", 8),
    ("AUTHORIZED", 5),
    ("DECLINED", 6),
    ("REVERSED", 3),
)

CLEARED_STATUSES = {"CLEARED", "SETTLED"}
CAPTURED_STATUSES = {"CAPTURED", "CLEARED", "SETTLED"}
AUTHORIZED_STATUSES = {"AUTHORIZED", "CAPTURED", "CLEARED", "SETTLED", "REVERSED"}

DECLINE_REASONS = (
    "issuer declined with response code 05",
    "issuer declined with response code 51",
    "card is BLOCKED and cannot be used",
)


@dataclass
class GeneratedBatch:
    """The rows for one batch, ready to be written."""

    payments: list[dict[str, Any]] = field(default_factory=list)
    ledger_entries: list[dict[str, Any]] = field(default_factory=list)
    clearing: list[dict[str, Any]] = field(default_factory=list)
    settlement: list[dict[str, Any]] = field(default_factory=list)


def _utc(dt: datetime) -> str:
    """ISO 8601 UTC with ``Z``, second precision, per the contract."""
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _business_window(business_date: date) -> tuple[datetime, datetime]:
    """The UTC instants bounding a business date.

    The cut-off is 22:00 Asia/Tokyo (UTC+9), so business date D runs from
    13:00Z on D-1 to 13:00Z on D. Japan observes no daylight saving, which is
    why a fixed offset is safe here.
    """
    jst = timezone(timedelta(hours=9))
    end = datetime.combine(business_date, time(22, 0), tzinfo=jst)
    return end - timedelta(days=1), end


def build(config: GeneratorConfig) -> GeneratedBatch:
    """Generate one batch of all four datasets."""
    rng = random.Random(config.seed)
    batch = GeneratedBatch()

    window_start, window_end = _business_window(config.business_date)
    window_seconds = int((window_end - window_start).total_seconds())

    # Identifiers are stamped with the start of the window rather than "now", so
    # a rerun produces the same UUIDs.
    base_millis = int(window_start.timestamp() * 1000)

    def new_id(offset: int = 0) -> str:
        return ids.uuid7(rng, base_millis + offset)

    issuers = [new_id(i) for i in range(config.issuer_count)]
    acquirers = [new_id(100 + i) for i in range(config.acquirer_count)]
    merchants = [
        {"merchant_id": new_id(200 + i), "acquirer_id": acquirers[i % len(acquirers)],
         "mcc": MCCS[i % len(MCCS)]}
        for i in range(max(len(acquirers) * 2, 4))
    ]

    statuses = [s for s, _ in STATUS_WEIGHTS]
    weights = [w for _, w in STATUS_WEIGHTS]

    for index in range(config.payment_count):
        merchant = merchants[rng.randrange(len(merchants))]
        issuer_id = issuers[rng.randrange(len(issuers))]
        status = rng.choices(statuses, weights=weights, k=1)[0]

        created_at = window_start + timedelta(seconds=rng.randrange(window_seconds))
        amount = Decimal(rng.randrange(config.min_amount, config.max_amount))

        payment_id = new_id(1000 + index * 7)
        correlation_id = new_id(1001 + index * 7)

        authorized = status in AUTHORIZED_STATUSES
        captured = status in CAPTURED_STATUSES

        # Partial capture happens, but not often — the hotel case.
        captured_amount = amount
        if captured and rng.random() < 0.12:
            captured_amount = money.round_money(amount * Decimal("0.6"), config.currency)

        refunded_amount = Decimal(0)
        if status == "SETTLED" and rng.random() < 0.15:
            refunded_amount = money.round_money(captured_amount * Decimal("0.5"), config.currency)

        authorized_at = created_at + timedelta(seconds=rng.randrange(1, 5)) if authorized else None
        captured_at = authorized_at + timedelta(hours=rng.randrange(1, 6)) if captured else None

        prefix = TEST_PAN_PREFIXES[rng.randrange(len(TEST_PAN_PREFIXES))]
        last4 = f"{rng.randrange(10000):04d}"

        batch.payments.append({
            "payment_id": payment_id,
            "correlation_id": correlation_id,
            "merchant_id": merchant["merchant_id"],
            "terminal_id": new_id(2000 + index) if rng.random() < 0.7 else "",
            "card_token": "tok_" + f"{rng.getrandbits(160):040x}",
            "pan_masked": f"{prefix}******{last4}",
            "card_scheme": CARD_SCHEMES[rng.randrange(len(CARD_SCHEMES))],
            "amount": money.format_money(amount, config.currency),
            "currency": config.currency,
            "authorized_amount": money.format_money(amount, config.currency) if authorized else "",
            "captured_amount": money.format_money(captured_amount, config.currency) if captured else "",
            "refunded_amount": money.format_money(refunded_amount, config.currency),
            "status": status,
            "mcc": merchant["mcc"],
            "auth_code": ids.auth_code(rng) if authorized else "",
            "risk_decision": "DECLINE" if status == "DECLINED" else "APPROVE",
            "risk_score": str(rng.randrange(0, 40)),
            "decline_reason": DECLINE_REASONS[rng.randrange(len(DECLINE_REASONS))] if status == "DECLINED" else "",
            "created_at": _utc(created_at),
            "authorized_at": _utc(authorized_at) if authorized_at else "",
            "captured_at": _utc(captured_at) if captured_at else "",
            "business_date": config.file_business_date.isoformat(),
            "batch_id": config.batch_id,
            "schema_version": str(SCHEMA_VERSION),
            # Not written to the file; used to build the other datasets.
            "_issuer_id": issuer_id,
            "_acquirer_id": merchant["acquirer_id"],
            "_captured_amount": captured_amount if captured else None,
            "_refunded_amount": refunded_amount,
            "_captured_at": captured_at,
        })

    _build_clearing(config, rng, batch, new_id)
    _build_ledger_entries(config, rng, batch, new_id)
    _build_settlement(config, batch, new_id)

    return batch


def _build_clearing(config, rng, batch: GeneratedBatch, new_id) -> None:
    """One row per cleared transaction, plus a negative row per refund."""
    index = 0
    for payment in batch.payments:
        if payment["status"] not in CLEARED_STATUSES:
            continue

        gross = payment["_captured_amount"]
        split = money.split_fees(
            gross, config.currency,
            config.merchant_discount_rate, config.interchange_rate, config.scheme_fee_rate)

        batch.clearing.append(_clearing_row(
            config, payment, new_id(5000 + index), split, "PURCHASE", payment["_captured_at"]))
        index += 1

        refunded = payment["_refunded_amount"]
        if refunded > 0:
            # A refund clears as a negative row, with every fee component
            # negated so the arithmetic reverses cleanly.
            refund_split = money.split_fees(
                refunded, config.currency,
                config.merchant_discount_rate, config.interchange_rate, config.scheme_fee_rate)
            negated = money.FeeSplit(
                gross=-refund_split.gross,
                merchant_discount=-refund_split.merchant_discount,
                interchange=-refund_split.interchange,
                scheme_fee=-refund_split.scheme_fee,
                acquirer_margin=-refund_split.acquirer_margin,
                net_to_merchant=-refund_split.net_to_merchant,
            )
            batch.clearing.append(_clearing_row(
                config, payment, new_id(5000 + index), negated, "REFUND",
                payment["_captured_at"] + timedelta(hours=2)))
            index += 1


def _clearing_row(config, payment, record_id, split, transaction_type, cleared_at) -> dict[str, Any]:
    fmt = lambda value: money.format_money(value, config.currency)
    return {
        "clearing_record_id": record_id,
        "batch_id": config.batch_id,
        "correlation_id": payment["correlation_id"],
        "payment_id": payment["payment_id"],
        "acquirer_id": payment["_acquirer_id"],
        "issuer_id": payment["_issuer_id"],
        "merchant_id": payment["merchant_id"],
        "gross_amount": fmt(split.gross),
        "interchange_fee": fmt(split.interchange),
        "scheme_fee": fmt(split.scheme_fee),
        "acquirer_margin": fmt(split.acquirer_margin),
        "net_to_merchant": fmt(split.net_to_merchant),
        "currency": config.currency,
        "transaction_type": transaction_type,
        "business_date": config.file_business_date.isoformat(),
        "cleared_at": _utc(cleared_at),
        "schema_version": str(SCHEMA_VERSION),
        "_split": split,
    }


def _build_ledger_entries(config, rng, batch: GeneratedBatch, new_id) -> None:
    """Capture and clearing journals, each balancing on its own.

    Capture posts the gross obligation; clearing adjusts both positions down to
    net and recognises the scheme fee. Mirrors ``LedgerService`` in payment-core.
    """
    index = 0

    def entry(journal_id, payment, account_code, account_type, direction, amount, journal_type, posted_at):
        nonlocal index
        index += 1
        return {
            "entry_id": new_id(20000 + index),
            "journal_id": journal_id,
            "correlation_id": payment["correlation_id"],
            "payment_id": payment["payment_id"],
            "account_code": account_code,
            "account_type": account_type,
            "direction": direction,
            "amount": money.format_money(amount, config.currency),
            "currency": config.currency,
            "journal_type": journal_type,
            "posted_at": _utc(posted_at),
            "business_date": config.file_business_date.isoformat(),
            "batch_id": config.batch_id,
            "schema_version": str(SCHEMA_VERSION),
        }

    for payment in batch.payments:
        if payment["status"] not in CAPTURED_STATUSES:
            continue

        gross = payment["_captured_amount"]
        posted_at = payment["_captured_at"]

        capture_journal = new_id(10000 + index)
        batch.ledger_entries.append(entry(
            capture_journal, payment, "DUE_FROM_ISSUER", "ASSET", "DEBIT", gross, "CAPTURE", posted_at))
        batch.ledger_entries.append(entry(
            capture_journal, payment, "DUE_TO_ACQUIRER", "LIABILITY", "CREDIT", gross, "CAPTURE", posted_at))

        if payment["status"] not in CLEARED_STATUSES:
            continue

        split = money.split_fees(
            gross, config.currency,
            config.merchant_discount_rate, config.interchange_rate, config.scheme_fee_rate)

        # Take the gross positions down to net: the issuer keeps interchange,
        # NexusPay keeps the scheme fee, the acquirer is owed the remainder.
        clearing_journal = new_id(10000 + index)
        batch.ledger_entries.append(entry(
            clearing_journal, payment, "DUE_TO_ACQUIRER", "LIABILITY", "DEBIT",
            split.interchange + split.scheme_fee, "CLEARING", posted_at))
        batch.ledger_entries.append(entry(
            clearing_journal, payment, "DUE_FROM_ISSUER", "ASSET", "CREDIT",
            split.interchange, "CLEARING", posted_at))
        batch.ledger_entries.append(entry(
            clearing_journal, payment, "SCHEME_FEE_REVENUE", "REVENUE", "CREDIT",
            split.scheme_fee, "CLEARING", posted_at))


def _build_settlement(config, batch: GeneratedBatch, new_id) -> None:
    """Net position per participant, derived from the clearing rows.

    NexusPay settles as a ``NETWORK`` participant. Without it the scheme fee has
    nowhere to go and the column fails to sum to zero — see
    ``docs/data-contracts.md`` section 6.
    """
    positions: dict[tuple[str, str], dict[str, Any]] = {}

    def position(participant_id: str, participant_type: str) -> dict[str, Any]:
        key = (participant_type, participant_id)
        if key not in positions:
            positions[key] = {
                "participant_id": participant_id,
                "participant_type": participant_type,
                "debits": Decimal(0),
                "credits": Decimal(0),
                "count": 0,
            }
        return positions[key]

    def apply(entry: dict[str, Any], amount: Decimal) -> None:
        """Positive means the participant pays; negative means it receives."""
        if amount >= 0:
            entry["debits"] += amount
        else:
            entry["credits"] += -amount
        entry["count"] += 1

    for row in batch.clearing:
        split = row["_split"]

        issuer_pays = split.gross - split.interchange
        acquirer_receives = split.gross - split.interchange - split.scheme_fee
        network_receives = split.scheme_fee

        apply(position(row["issuer_id"], "ISSUER"), issuer_pays)
        apply(position(row["acquirer_id"], "ACQUIRER"), -acquirer_receives)
        apply(position("NEXUSPAY", "NETWORK"), -network_receives)

    ordered = sorted(positions.values(), key=lambda p: (p["participant_type"], p["participant_id"]))

    skip = config.faults.missing_settlement_lines
    for index, entry in enumerate(ordered):
        if skip and index < skip:
            continue

        net = entry["debits"] - entry["credits"]
        batch.settlement.append({
            "settlement_id": new_id(30000 + index),
            "batch_id": config.batch_id,
            "participant_id": entry["participant_id"],
            "participant_type": entry["participant_type"],
            "business_date": config.file_business_date.isoformat(),
            "gross_debits": money.format_money(entry["debits"], config.currency),
            "gross_credits": money.format_money(entry["credits"], config.currency),
            "net_amount": money.format_money(net, config.currency),
            "currency": config.currency,
            "transaction_count": str(entry["count"]),
            "status": "EXECUTED",
            "executed_at": _utc(_business_window(config.business_date)[1] + timedelta(hours=12)),
            "schema_version": str(SCHEMA_VERSION),
        })
