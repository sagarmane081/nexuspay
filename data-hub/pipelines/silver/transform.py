"""Silver transformations.

Every function here takes a DataFrame and returns a DataFrame, with no reference
to a path, a table name or a SparkSession. That is what lets the same code run
under local pytest and on Databricks in Phase 5 — and it is also what makes the
rules testable against a handful of hand-built rows instead of a whole pipeline.

The order matters and is not arbitrary:

    validate (strings)  ->  split  ->  cast  ->  deduplicate

Validating first preserves the evidence. Casting first would turn ``"ZZZ"`` into
a null and ``"-5000"`` into a plain number, and the quarantine row would then
have to say "something was null" rather than "the currency was ZZZ".
"""

from __future__ import annotations

from pyspark.sql import DataFrame, Window, functions as F
from pyspark.sql.types import DecimalType

from generator.writer import COLUMNS
from pipelines.silver.rules import (
    MASKED_PAN_PATTERN,
    QUARANTINE_DETAIL,
    QUARANTINE_REASON,
    reason_column,
)

#: The natural key of each dataset, used for deduplication.
BUSINESS_KEYS = {
    "payments": ("payment_id",),
    "ledger_entries": ("entry_id",),
    "clearing": ("clearing_record_id",),
    "settlement": ("participant_id", "participant_type", "business_date"),
}

MONEY = DecimalType(18, 4)

#: Target types. Anything unlisted stays a string.
CASTS: dict[str, dict[str, object]] = {
    "payments": {
        "amount": MONEY, "authorized_amount": MONEY, "captured_amount": MONEY,
        "refunded_amount": MONEY, "risk_score": "int", "schema_version": "int",
        "created_at": "timestamp", "authorized_at": "timestamp", "captured_at": "timestamp",
        "business_date": "date",
    },
    "ledger_entries": {
        "amount": MONEY, "schema_version": "int",
        "posted_at": "timestamp", "business_date": "date",
    },
    "clearing": {
        "gross_amount": MONEY, "interchange_fee": MONEY, "scheme_fee": MONEY,
        "acquirer_margin": MONEY, "net_to_merchant": MONEY, "schema_version": "int",
        "cleared_at": "timestamp", "business_date": "date",
    },
    "settlement": {
        "gross_debits": MONEY, "gross_credits": MONEY, "net_amount": MONEY,
        "transaction_count": "int", "schema_version": "int",
        "executed_at": "timestamp", "business_date": "date",
    },
}


class UnbalancedLedger(Exception):
    """A journal's debits do not equal its credits.

    Deliberately fatal rather than quarantined. ``docs/data-contracts.md``
    section 9 says so explicitly, and the reasoning is that quarantining an
    unbalanced journal would let the pipeline report success while money had
    been created or destroyed. Every other defect affects some rows; this one
    invalidates the ledger.
    """


def validate(frame: DataFrame, dataset: str) -> DataFrame:
    """Attach a quarantine reason to every row that breaks a rule."""
    return frame.withColumn(QUARANTINE_REASON, reason_column(dataset))


def split(frame: DataFrame) -> tuple[DataFrame, DataFrame]:
    """Separate clean rows from quarantined ones."""
    clean = frame.filter(F.col(QUARANTINE_REASON).isNull()).drop(QUARANTINE_REASON)
    quarantined = frame.filter(F.col(QUARANTINE_REASON).isNotNull())
    return clean, quarantined


def redact_quarantine(frame: DataFrame) -> DataFrame:
    """Strip card data from rows on their way to quarantine.

    The whole point of the ``UNMASKED_PAN`` rule is that a full card number
    reached us. Writing that row to a quarantine table verbatim would move the
    leak rather than contain it — the PAN would simply live somewhere with less
    scrutiny. Anything not already in ``first6 + mask + last4`` form is replaced.
    """
    if "pan_masked" not in frame.columns:
        return frame

    return frame.withColumn(
        "pan_masked",
        F.when(F.col("pan_masked").rlike(MASKED_PAN_PATTERN), F.col("pan_masked"))
        .otherwise(F.lit("***REDACTED***")),
    )


def describe_quarantine(frame: DataFrame, dataset: str) -> DataFrame:
    """Add the human-readable detail an operator needs to act on a row."""
    detail = F.concat_ws(
        " ",
        F.lit(f"dataset={dataset}"),
        F.concat(F.lit("source_file="), F.coalesce(F.col("source_file"), F.lit("?"))),
    )
    return frame.withColumn(QUARANTINE_DETAIL, detail)


def cast_types(frame: DataFrame, dataset: str) -> DataFrame:
    """Cast the conformed columns to their real types.

    Runs only on rows that already passed validation, so a null after this point
    means the source genuinely sent nothing — never that a value was rejected.
    """
    casted = frame
    for column, target in CASTS[dataset].items():
        if column in casted.columns:
            casted = casted.withColumn(column, F.col(column).cast(target))
    return casted


def deduplicate(frame: DataFrame, dataset: str) -> DataFrame:
    """Keep one row per business key.

    A producer replaying part of a batch, or a retry that made it through twice,
    puts the same business key in the file more than once. Bronze keeps both
    because Bronze keeps everything; Silver is where the duplicate is resolved.

    The most recently ingested row wins, with ``source_file`` breaking ties so
    the result does not depend on the order Spark happened to read partitions —
    a non-deterministic dedup is worse than none, because it is not reproducible.
    """
    keys = [F.col(key) for key in BUSINESS_KEYS[dataset]]
    ordering = [F.col("ingested_at").desc(), F.col("source_file").asc()]

    window = Window.partitionBy(*keys).orderBy(*ordering)
    return (
        frame
        .withColumn("_row_number", F.row_number().over(window))
        .filter(F.col("_row_number") == 1)
        .drop("_row_number")
    )


def assert_journals_balance(frame: DataFrame) -> None:
    """Raise unless every journal's debits equal its credits, per currency.

    Checked on the typed, deduplicated Silver rows. Quarantined entries are
    already gone by this point, which is the right order: an entry that failed
    validation should not be counted towards a balance it was never part of.
    """
    signed = F.when(F.col("direction") == "DEBIT", F.col("amount")).otherwise(-F.col("amount"))

    offenders = (
        frame
        .groupBy("journal_id", "currency")
        .agg(F.sum(signed).alias("imbalance"))
        .filter(F.col("imbalance") != 0)
        .limit(5)
        .collect()
    )

    if offenders:
        detail = ", ".join(
            f"{row['journal_id']} off by {row['imbalance']} {row['currency']}" for row in offenders)
        raise UnbalancedLedger(f"journals do not balance: {detail}")


def to_silver(frame: DataFrame, dataset: str) -> tuple[DataFrame, DataFrame]:
    """The whole Silver transformation, as one pure function.

    Returns ``(clean, quarantined)``.
    """
    validated = validate(frame, dataset)
    clean, quarantined = split(validated)

    clean = deduplicate(cast_types(clean, dataset), dataset)
    quarantined = describe_quarantine(redact_quarantine(quarantined), dataset)

    if dataset == "ledger_entries":
        assert_journals_balance(clean)

    return clean, quarantined


def silver_columns(dataset: str) -> list[str]:
    """Contract columns plus Bronze's lineage, in a stable order."""
    return list(COLUMNS[dataset]) + ["source_file", "ingested_at"]
