"""Bronze ingestion: land the files exactly as they arrived.

Bronze's job is to be a faithful, append-only record of what the producer sent.
That means one rule above all others: **read everything as a string and reject
nothing**.

It is tempting to cast types here and drop the rows that fail. Doing so destroys
the evidence. A row with ``amount = "-5000"`` or ``currency = "ZZZ"`` must reach
Silver so it can be quarantined *with a reason* that somebody can act on. If
Bronze casts, the row becomes a null or vanishes, and the only remaining signal
is a count that does not add up.

The one thing Bronze does refuse is a file whose name disagrees with its
contents — see :func:`_assert_file_name_matches_contents`.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from pyspark.sql import DataFrame, SparkSession, functions as F
from pyspark.sql.types import StringType, StructField, StructType

from generator.writer import COLUMNS

#: Stamped by Bronze, never sent by the producer.
RESCUED_COLUMN = "_rescued_data"
LINEAGE_COLUMNS = ("source_file", "ingested_at")

FILE_NAME_PATTERN = re.compile(
    r"^(?P<dataset>[a-z_]+)_(?P<business_date>\d{4}-\d{2}-\d{2})"
    r"_(?P<batch_id>B\d{11})_v(?P<schema_version>\d+)\.csv$"
)


class ContractViolation(Exception):
    """The file disagrees with its own name, so nothing about it can be trusted."""


@dataclass(frozen=True)
class IngestResult:
    dataset: str
    files_ingested: list[str]
    files_skipped: list[str]
    rows_ingested: int

    @property
    def was_noop(self) -> bool:
        return not self.files_ingested


def bronze_schema(dataset: str) -> StructType:
    """Every contract column as a string, plus the rescue column.

    All strings on purpose. Typing happens in Silver, where a cast failure can
    be turned into a quarantine row with an explanation instead of a null.
    """
    fields = [StructField(name, StringType(), nullable=True) for name in COLUMNS[dataset]]
    fields.append(StructField(RESCUED_COLUMN, StringType(), nullable=True))
    return StructType(fields)


def parse_file_name(name: str) -> dict[str, str]:
    match = FILE_NAME_PATTERN.match(name)
    if not match:
        raise ContractViolation(f"file name does not follow the contract: {name}")
    return match.groupdict()


def discover(landing_dir: Path, dataset: str) -> list[Path]:
    """Contract-shaped files for one dataset, oldest batch first."""
    directory = landing_dir / dataset
    if not directory.is_dir():
        return []
    return sorted(p for p in directory.glob(f"{dataset}_*.csv") if FILE_NAME_PATTERN.match(p.name))


def already_ingested(spark: SparkSession, bronze_path: Path) -> set[str]:
    """Source files already present in the Bronze table.

    This is what makes re-ingestion a no-op. Delta gives atomic appends, not
    deduplication — replaying a file would cheerfully double every row.
    """
    if not (bronze_path / "_delta_log").is_dir():
        return set()

    rows = spark.read.format("delta").load(str(bronze_path)).select("source_file").distinct().collect()
    return {row["source_file"] for row in rows}


def _assert_file_name_matches_contents(frame: DataFrame, dataset: str, expected: dict[str, str]) -> None:
    """A file name is a fact, not a hint.

    ``docs/data-contracts.md`` section 1 requires Bronze to fail loudly when the
    ``batch_id`` or ``schema_version`` in the name disagrees with the rows. A
    mismatch means either the producer mislabelled the file or two batches were
    concatenated — and in both cases every downstream lineage claim would be a
    lie.
    """
    mismatched = frame.filter(
        (F.col("batch_id") != F.lit(expected["batch_id"]))
        | (F.col("schema_version") != F.lit(expected["schema_version"]))
    )

    offender = mismatched.select("batch_id", "schema_version").limit(1).collect()
    if offender:
        row = offender[0]
        raise ContractViolation(
            f"{dataset} file claims batch {expected['batch_id']} v{expected['schema_version']} "
            f"but contains rows for batch {row['batch_id']} v{row['schema_version']}"
        )


def ingest(
    spark: SparkSession,
    dataset: str,
    landing_dir: Path,
    bronze_root: Path,
    ingested_at: datetime | None = None,
) -> IngestResult:
    """Append every not-yet-seen file for one dataset into its Bronze table."""
    if dataset not in COLUMNS:
        raise ValueError(f"unknown dataset: {dataset}")

    bronze_path = bronze_root / dataset
    seen = already_ingested(spark, bronze_path)

    candidates = discover(landing_dir, dataset)
    new_files = [path for path in candidates if path.name not in seen]
    skipped = [path.name for path in candidates if path.name in seen]

    if not new_files:
        return IngestResult(dataset, [], skipped, 0)

    stamp = ingested_at or datetime.now(timezone.utc)
    schema = bronze_schema(dataset)
    total_rows = 0

    # One file at a time: each has its own batch_id and schema_version to
    # verify, and a contract violation should name the offending file.
    for path in new_files:
        expected = parse_file_name(path.name)

        frame = (
            spark.read
            .option("header", "true")
            .option("mode", "PERMISSIVE")
            .option("columnNameOfCorruptRecord", RESCUED_COLUMN)
            # Keep whitespace exactly as sent; Bronze is a faithful record.
            .option("ignoreLeadingWhiteSpace", "false")
            .option("ignoreTrailingWhiteSpace", "false")
            .schema(schema)
            .csv(str(path))
        )

        _assert_file_name_matches_contents(frame, dataset, expected)

        stamped = (
            frame
            .withColumn("source_file", F.lit(path.name))
            .withColumn("ingested_at", F.lit(stamp.isoformat()))
        )

        total_rows += stamped.count()
        (stamped.write
         .format("delta")
         .mode("append")          # Bronze is append-only. Never overwrite.
         .option("mergeSchema", "false")
         .save(str(bronze_path)))

    return IngestResult(dataset, [path.name for path in new_files], skipped, total_rows)


def ingest_all(
    spark: SparkSession,
    landing_dir: Path,
    bronze_root: Path,
    ingested_at: datetime | None = None,
) -> dict[str, IngestResult]:
    """Ingest every dataset defined by the contract."""
    return {
        dataset: ingest(spark, dataset, landing_dir, bronze_root, ingested_at)
        for dataset in COLUMNS
    }


def read_bronze(spark: SparkSession, bronze_root: Path, dataset: str) -> DataFrame:
    return spark.read.format("delta").load(str(bronze_root / dataset))
