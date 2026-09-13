"""Writes the generated batch as CSV, exactly per ``docs/data-contracts.md``.

The column lists below are the contract. They are written out in full, in order,
rather than derived from the row dictionaries — if the two ever drift apart, the
contract test fails loudly instead of the file quietly changing shape.
"""

from __future__ import annotations

import csv
import io
import random
from pathlib import Path
from typing import Any

from generator.config import SCHEMA_VERSION, GeneratorConfig
from generator.datasets import GeneratedBatch

COLUMNS: dict[str, tuple[str, ...]] = {
    "payments": (
        "payment_id", "correlation_id", "merchant_id", "terminal_id", "card_token",
        "pan_masked", "card_scheme", "amount", "currency", "authorized_amount",
        "captured_amount", "refunded_amount", "status", "mcc", "auth_code",
        "risk_decision", "risk_score", "decline_reason", "created_at",
        "authorized_at", "captured_at", "business_date", "batch_id", "schema_version",
    ),
    "ledger_entries": (
        "entry_id", "journal_id", "correlation_id", "payment_id", "account_code",
        "account_type", "direction", "amount", "currency", "journal_type",
        "posted_at", "business_date", "batch_id", "schema_version",
    ),
    "clearing": (
        "clearing_record_id", "batch_id", "correlation_id", "payment_id",
        "acquirer_id", "issuer_id", "merchant_id", "gross_amount",
        "interchange_fee", "scheme_fee", "acquirer_margin", "net_to_merchant",
        "currency", "transaction_type", "business_date", "cleared_at", "schema_version",
    ),
    "settlement": (
        "settlement_id", "batch_id", "participant_id", "participant_type",
        "business_date", "gross_debits", "gross_credits", "net_amount",
        "currency", "transaction_count", "status", "executed_at", "schema_version",
    ),
}


def file_name(dataset: str, config: GeneratorConfig) -> str:
    """``{dataset}_{business_date}_{batch_id}_v{schema_version}.csv``"""
    return (
        f"{dataset}_{config.file_business_date:%Y-%m-%d}"
        f"_{config.batch_id}_v{SCHEMA_VERSION}.csv"
    )


def render(dataset: str, rows: list[dict[str, Any]], config: GeneratorConfig) -> str:
    """Render one dataset to CSV text.

    ``newline=""`` plus ``lineterminator="\\n"`` gives the LF endings the
    contract requires, on Windows as well as anywhere else — the default would
    emit CRLF here and silently break byte-for-byte comparisons.
    """
    columns = list(COLUMNS[dataset])
    if config.faults.extra_column and dataset == "payments":
        columns.append("unexpected_column")

    buffer = io.StringIO(newline="")
    writer = csv.DictWriter(
        buffer,
        fieldnames=columns,
        lineterminator="\n",
        quoting=csv.QUOTE_MINIMAL,
        extrasaction="ignore",
    )
    writer.writeheader()

    for row in rows:
        if config.faults.extra_column and dataset == "payments":
            row = dict(row, unexpected_column="surprise")
        writer.writerow(row)

    text = buffer.getvalue()

    if config.faults.truncate_last_row and dataset == "payments":
        # Chop the final row mid-field, the way a writer killed part-way
        # through leaves a file. The newline goes too, so the row is
        # unterminated as well as incomplete.
        lines = text.split("\n")
        while lines and lines[-1] == "":
            lines.pop()
        if lines:
            lines[-1] = lines[-1][: max(1, len(lines[-1]) // 2)]
        text = "\n".join(lines)

    return text


def apply_row_faults(batch: GeneratedBatch, config: GeneratorConfig) -> None:
    """Inject the defects that live in the rows rather than the file text."""
    faults = config.faults
    rng = random.Random(config.seed + 1)

    if faults.duplicate_payments:
        # The same payment_id twice in one file. Bronze keeps both; Silver must
        # deduplicate by business key.
        for index in range(min(faults.duplicate_payments, len(batch.payments))):
            batch.payments.append(dict(batch.payments[index]))

    if faults.negative_amounts:
        for index in range(min(faults.negative_amounts, len(batch.payments))):
            row = batch.payments[index]
            row["amount"] = "-" + row["amount"].lstrip("-")

    if faults.unknown_currencies:
        for index in range(min(faults.unknown_currencies, len(batch.payments))):
            batch.payments[index]["currency"] = "ZZZ"

    if faults.unmasked_pans:
        # A full PAN where a masked one belongs: a contract breach and a
        # security incident, and the pipeline must quarantine it rather than
        # carry it forward.
        for index in range(min(faults.unmasked_pans, len(batch.payments))):
            batch.payments[index]["pan_masked"] = "4111111111111111"

    if faults.unbalanced_journals:
        # Break the credit side of N journals by one unit. Nothing may silently
        # absorb this: an unbalanced journal must fail the pipeline, never be
        # quarantined and forgotten.
        credits = [entry for entry in batch.ledger_entries if entry["direction"] == "CREDIT"]
        for index in range(min(faults.unbalanced_journals, len(credits))):
            entry = credits[index]
            entry["amount"] = str(int(entry["amount"]) - 1)

    del rng  # reserved for future faults that need randomness


def write(batch: GeneratedBatch, config: GeneratorConfig, output_dir: Path) -> dict[str, Path]:
    """Write all four datasets. Returns dataset name to the path written."""
    apply_row_faults(batch, config)

    written: dict[str, Path] = {}
    datasets = {
        "payments": batch.payments,
        "ledger_entries": batch.ledger_entries,
        "clearing": batch.clearing,
        "settlement": batch.settlement,
    }

    for dataset, rows in datasets.items():
        directory = output_dir / dataset
        directory.mkdir(parents=True, exist_ok=True)

        path = directory / file_name(dataset, config)
        # newline="" stops the csv module's endings being rewritten; encoding is
        # UTF-8 without a BOM, as the contract requires.
        path.write_text(render(dataset, rows, config), encoding="utf-8", newline="")
        written[dataset] = path

    return written
