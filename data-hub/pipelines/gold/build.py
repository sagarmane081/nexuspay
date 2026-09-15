"""Gold IO: read Silver, build the marts, write them.

All logic lives in :mod:`pipelines.gold.marts` as pure DataFrame functions;
this module only knows about paths.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from pyspark.sql import DataFrame, SparkSession

from pipelines.bronze import read_bronze
from pipelines.gold.marts import (
    ledger_balance,
    merchant_revenue,
    reconciliation,
    settlement_positions,
)
from pipelines.silver import read_quarantine, read_silver

#: dataset -> the money column reconciliation should total.
AMOUNT_COLUMNS = {
    "payments": "amount",
    "ledger_entries": "amount",
    "clearing": "gross_amount",
    "settlement": "net_amount",
}

MARTS = ("merchant_revenue", "settlement_positions", "ledger_balance", "reconciliation")


@dataclass(frozen=True)
class GoldResult:
    mart: str
    rows: int


def _write(frame: DataFrame, gold_root: Path, mart: str) -> GoldResult:
    # Gold is derived, so overwriting is safe and makes a rebuild idempotent.
    # Bronze stays the append-only record everything can be rebuilt from.
    (frame.write.format("delta").mode("overwrite")
        .option("overwriteSchema", "true")
        .save(str(gold_root / mart)))
    return GoldResult(mart, frame.count())


def build_all(
    spark: SparkSession,
    bronze_root: Path,
    silver_root: Path,
    quarantine_root: Path,
    gold_root: Path,
) -> dict[str, GoldResult]:
    """Build every Gold mart from Silver, plus the reconciliation mart."""
    results: dict[str, GoldResult] = {}

    results["merchant_revenue"] = _write(
        merchant_revenue(read_silver(spark, silver_root, "clearing")), gold_root, "merchant_revenue")

    results["settlement_positions"] = _write(
        settlement_positions(read_silver(spark, silver_root, "settlement")),
        gold_root, "settlement_positions")

    results["ledger_balance"] = _write(
        ledger_balance(read_silver(spark, silver_root, "ledger_entries")),
        gold_root, "ledger_balance")

    frames = []
    for dataset, amount_column in AMOUNT_COLUMNS.items():
        frames.append(reconciliation(
            dataset,
            read_bronze(spark, bronze_root, dataset),
            read_silver(spark, silver_root, dataset),
            read_quarantine(spark, quarantine_root, dataset),
            amount_column,
        ))

    combined = frames[0]
    for frame in frames[1:]:
        combined = combined.unionByName(frame)

    results["reconciliation"] = _write(combined, gold_root, "reconciliation")
    return results


def read_gold(spark: SparkSession, gold_root: Path, mart: str) -> DataFrame:
    return spark.read.format("delta").load(str(gold_root / mart))
