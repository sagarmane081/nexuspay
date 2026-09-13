"""Silver IO: read Bronze, transform, write the clean and quarantined tables.

All the logic lives in :mod:`pipelines.silver.transform` as pure DataFrame
functions. This module only knows about paths, which keeps the interesting part
runnable on Databricks unchanged.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from pyspark.sql import DataFrame, SparkSession, functions as F

from generator.writer import COLUMNS
from pipelines.bronze import read_bronze
from pipelines.silver.transform import silver_columns, to_silver


@dataclass(frozen=True)
class SilverResult:
    dataset: str
    rows_in: int
    rows_clean: int
    rows_quarantined: int
    duplicates_removed: int


def build(
    spark: SparkSession,
    dataset: str,
    bronze_root: Path,
    silver_root: Path,
    quarantine_root: Path,
    processed_at: datetime | None = None,
) -> SilverResult:
    """Rebuild one Silver table from Bronze.

    Silver is rebuilt rather than appended: it is a *derived* view of Bronze, so
    overwriting is safe and makes reprocessing idempotent by construction. Bronze
    remains the append-only record — if Silver is wrong, it can simply be built
    again from data that was never mutated.
    """
    if dataset not in COLUMNS:
        raise ValueError(f"unknown dataset: {dataset}")

    stamp = processed_at or datetime.now(timezone.utc)
    bronze = read_bronze(spark, bronze_root, dataset)
    rows_in = bronze.count()

    clean, quarantined = to_silver(bronze, dataset)

    clean = clean.select(*silver_columns(dataset))
    rows_clean = clean.count()
    rows_quarantined = quarantined.count()

    (clean.write.format("delta").mode("overwrite")
        .option("overwriteSchema", "true")
        .save(str(silver_root / dataset)))

    (quarantined
        .withColumn("_quarantined_at", F.lit(stamp.isoformat()))
        .write.format("delta").mode("overwrite")
        .option("overwriteSchema", "true")
        .save(str(quarantine_root / dataset)))

    return SilverResult(
        dataset=dataset,
        rows_in=rows_in,
        rows_clean=rows_clean,
        rows_quarantined=rows_quarantined,
        duplicates_removed=rows_in - rows_clean - rows_quarantined,
    )


def build_all(
    spark: SparkSession,
    bronze_root: Path,
    silver_root: Path,
    quarantine_root: Path,
    processed_at: datetime | None = None,
) -> dict[str, SilverResult]:
    return {
        dataset: build(spark, dataset, bronze_root, silver_root, quarantine_root, processed_at)
        for dataset in COLUMNS
    }


def read_silver(spark: SparkSession, silver_root: Path, dataset: str) -> DataFrame:
    return spark.read.format("delta").load(str(silver_root / dataset))


def read_quarantine(spark: SparkSession, quarantine_root: Path, dataset: str) -> DataFrame:
    return spark.read.format("delta").load(str(quarantine_root / dataset))
