"""Gold marts: business-ready aggregates.

Pure DataFrame functions, like Silver, so the same code runs under pytest and on
Databricks in Phase 5.

Two things every mart here carries, without exception:

* **``batch_id``** — the batch the numbers came from.
* **``source_files``** — the files behind the row.

Aggregation is where lineage usually dies: you sum a million rows into one and
the answer to "where did this number come from?" becomes "somewhere". An
unexplainable figure in a settlement report is worse than no report, because
somebody will act on it. Carrying the batch and the file list costs almost
nothing and makes every Gold row answerable.
"""

from __future__ import annotations

from pyspark.sql import DataFrame, functions as F

#: Present on every Gold table. Asserted by the Phase 2.4 lineage test.
LINEAGE_COLUMNS = ("batch_id", "source_files")


def _with_lineage(grouped: DataFrame) -> DataFrame:
    """Nothing yet — lineage is built into each aggregation below.

    Kept as documentation of the rule: if a mart is added without
    ``batch_id`` and ``source_files``, the lineage test fails.
    """
    return grouped


def merchant_revenue(clearing: DataFrame) -> DataFrame:
    """What each merchant earned, and what it cost them, per business date.

    Built from clearing rather than payments because clearing is where fees are
    known. Refunds arrive as negative rows, so summing handles them naturally —
    a merchant's gross for the day is purchases less returns, which is what the
    merchant actually experienced.
    """
    return (
        clearing
        .groupBy("merchant_id", "business_date", "currency", "batch_id")
        .agg(
            F.count(F.lit(1)).alias("transaction_count"),
            F.sum(F.when(F.col("transaction_type") == "PURCHASE", 1).otherwise(0))
                .alias("purchase_count"),
            F.sum(F.when(F.col("transaction_type") == "REFUND", 1).otherwise(0))
                .alias("refund_count"),
            F.sum("gross_amount").alias("gross_amount"),
            F.sum("interchange_fee").alias("interchange_fee"),
            F.sum("scheme_fee").alias("scheme_fee"),
            F.sum("acquirer_margin").alias("acquirer_margin"),
            F.sum("net_to_merchant").alias("net_to_merchant"),
            F.sort_array(F.collect_set("source_file")).alias("source_files"),
        )
    )


def settlement_positions(settlement: DataFrame) -> DataFrame:
    """What each participant owes or is owed, per business date.

    Already one row per participant per date in Silver, so this is a conformed
    pass rather than a real aggregation — but it is grouped anyway so that two
    batches covering the same date combine rather than appearing twice and
    quietly doubling a participant's position.
    """
    return (
        settlement
        .groupBy("participant_id", "participant_type", "business_date", "currency", "batch_id")
        .agg(
            F.sum("gross_debits").alias("gross_debits"),
            F.sum("gross_credits").alias("gross_credits"),
            F.sum("net_amount").alias("net_amount"),
            F.sum("transaction_count").alias("transaction_count"),
            F.sort_array(F.collect_set("source_file")).alias("source_files"),
        )
    )


def ledger_balance(ledger_entries: DataFrame) -> DataFrame:
    """Debits and credits per journal — the shape the balance check needs."""
    return (
        ledger_entries
        .groupBy("journal_id", "currency", "business_date", "batch_id")
        .agg(
            F.sum(F.when(F.col("direction") == "DEBIT", F.col("amount"))
                  .otherwise(0)).alias("debits"),
            F.sum(F.when(F.col("direction") == "CREDIT", F.col("amount"))
                  .otherwise(0)).alias("credits"),
            F.sort_array(F.collect_set("source_file")).alias("source_files"),
        )
        .withColumn("imbalance", F.col("debits") - F.col("credits"))
    )


def reconciliation(
    dataset: str,
    bronze: DataFrame,
    silver: DataFrame,
    quarantine: DataFrame,
    amount_column: str | None = None,
) -> DataFrame:
    """One row per batch describing whether a layer lost anything.

    This is the mart an operator looks at when a number seems wrong. It answers
    the only two questions that matter at a layer boundary: did every row arrive
    somewhere, and does the money still add up?

    ``rows_unaccounted`` is the important column. Clean plus quarantined plus
    deduplicated must equal what Bronze received. Anything else means a row was
    dropped silently, which is the failure the whole quarantine design exists to
    prevent.
    """
    def counted(frame: DataFrame, label: str) -> DataFrame:
        aggregates = [F.count(F.lit(1)).alias(f"{label}_rows")]
        if amount_column and amount_column in frame.columns:
            aggregates.append(
                F.coalesce(F.sum(F.col(amount_column).cast("decimal(18,4)")), F.lit(0))
                .alias(f"{label}_amount"))
        return frame.groupBy("batch_id").agg(*aggregates)

    joined = (
        counted(bronze, "bronze")
        .join(counted(silver, "silver"), on="batch_id", how="left")
        .join(counted(quarantine, "quarantine"), on="batch_id", how="left")
    )

    for column in joined.columns:
        if column != "batch_id":
            joined = joined.withColumn(column, F.coalesce(F.col(column), F.lit(0)))

    joined = (
        joined
        .withColumn("dataset", F.lit(dataset))
        # Bronze rows that reached neither Silver nor quarantine, beyond the
        # duplicates Silver legitimately collapsed.
        .withColumn("rows_accounted",
                    F.col("silver_rows") + F.col("quarantine_rows"))
        .withColumn("rows_unaccounted",
                    F.col("bronze_rows") - F.col("rows_accounted"))
    )

    if amount_column:
        joined = joined.withColumn(
            "amount_dropped",
            F.col("bronze_amount") - F.col("silver_amount") - F.col("quarantine_amount"))

    return joined
