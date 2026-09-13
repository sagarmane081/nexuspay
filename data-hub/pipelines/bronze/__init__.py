"""Bronze layer: raw, append-only, faithful to the source."""

from pipelines.bronze.ingest import (
    ContractViolation,
    IngestResult,
    bronze_schema,
    discover,
    ingest,
    ingest_all,
    parse_file_name,
    read_bronze,
)

__all__ = [
    "ContractViolation",
    "IngestResult",
    "bronze_schema",
    "discover",
    "ingest",
    "ingest_all",
    "parse_file_name",
    "read_bronze",
]
