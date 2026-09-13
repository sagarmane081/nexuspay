"""Phase 2.3: Silver is conformed, deduplicated, and bad rows are quarantined
with a reason.

The three required tests are ``test_duplicates_are_removed``,
``test_every_quarantined_row_has_a_reason`` and ``test_no_raw_pan_in_silver``.
Everything else supports them.
"""

from __future__ import annotations

import re
from datetime import date, datetime, timezone
from decimal import Decimal

import pytest

from generator import Faults, GeneratorConfig, generate
from pipelines.bronze import ingest, ingest_all, read_bronze
from pipelines.silver import (
    QUARANTINE_DETAIL,
    QUARANTINE_REASON,
    UnbalancedLedger,
    build,
    build_all,
    deduplicate,
    read_quarantine,
    read_silver,
    to_silver,
)

BUSINESS_DATE = date(2026, 9, 14)
INGESTED_AT = datetime(2026, 9, 15, 3, 0, tzinfo=timezone.utc)
PROCESSED_AT = datetime(2026, 9, 15, 4, 0, tzinfo=timezone.utc)

RAW_PAN = re.compile(r"\b\d{13,19}\b")

ALL_DATASETS = ("payments", "ledger_entries", "clearing", "settlement")


def config(**overrides) -> GeneratorConfig:
    # Small on purpose. Every test drives a real Spark job, and the rules under
    # test are row-level — 30 rows exercise them exactly as well as 600 do, at a
    # fraction of the runtime.
    defaults = dict(business_date=BUSINESS_DATE, seed=42, payment_count=30)
    return GeneratorConfig(**{**defaults, **overrides})


@pytest.fixture
def lakehouse(tmp_path):
    """Landing, Bronze, Silver and quarantine roots for one test."""
    return {
        "landing": tmp_path / "landing",
        "bronze": tmp_path / "bronze",
        "silver": tmp_path / "silver",
        "quarantine": tmp_path / "quarantine",
    }


def run_pipeline(spark, paths, faults: Faults | None = None, datasets=("payments",), **cfg):
    """Generate, ingest and build Silver.

    ``datasets`` defaults to payments alone: most rules are per-dataset, and
    pushing all four through Bronze and Silver for every test tripled the
    suite's runtime for no extra coverage. Tests that genuinely span datasets
    pass ``datasets=ALL_DATASETS``.
    """
    written = generate(config(faults=faults or Faults(), **cfg), paths["landing"])

    results = {}
    for dataset in datasets:
        ingest(spark, dataset, paths["landing"], paths["bronze"], INGESTED_AT)
        results[dataset] = build(
            spark, dataset, paths["bronze"], paths["silver"], paths["quarantine"], PROCESSED_AT)

    return written, results


# ---------------------------------------------------------------------------
# Required test 1: duplicates are removed
# ---------------------------------------------------------------------------


class TestDeduplication:

    def test_duplicates_are_removed(self, spark, lakehouse):
        _, results = run_pipeline(spark, lakehouse, Faults(duplicate_payments=5))

        bronze = read_bronze(spark, lakehouse["bronze"], "payments")
        silver = read_silver(spark, lakehouse["silver"], "payments")

        assert bronze.count() == silver.count() + 5, "Bronze should still hold the duplicates"
        assert results["payments"].duplicates_removed == 5

        distinct = silver.select("payment_id").distinct().count()
        assert distinct == silver.count(), "Silver still contains duplicate business keys"

    def test_deduplication_keeps_exactly_one_row_per_key(self, spark, lakehouse):
        run_pipeline(spark, lakehouse, Faults(duplicate_payments=3))
        silver = read_silver(spark, lakehouse["silver"], "payments")

        repeated = (silver.groupBy("payment_id").count()
                    .filter("count > 1").collect())
        assert repeated == []

    def test_deduplication_is_deterministic(self, spark, lakehouse):
        """Two runs over the same Bronze must pick the same surviving row.

        Ordering by ingested_at alone would leave ties broken by whatever order
        Spark happened to read partitions in — a dedup that is not reproducible
        is barely better than none.
        """
        run_pipeline(spark, lakehouse, Faults(duplicate_payments=4))
        first = {r["payment_id"]: r["card_token"]
                 for r in read_silver(spark, lakehouse["silver"], "payments").collect()}

        build(spark, "payments", lakehouse["bronze"], lakehouse["silver"],
              lakehouse["quarantine"], PROCESSED_AT)
        second = {r["payment_id"]: r["card_token"]
                  for r in read_silver(spark, lakehouse["silver"], "payments").collect()}

        assert first == second

    def test_settlement_dedups_on_a_composite_key(self, spark, lakehouse):
        run_pipeline(spark, lakehouse, datasets=("settlement",))
        silver = read_silver(spark, lakehouse["silver"], "settlement")

        repeated = (silver.groupBy("participant_id", "participant_type", "business_date")
                    .count().filter("count > 1").collect())
        assert repeated == [], "one participant may settle only once per business date"


# ---------------------------------------------------------------------------
# Required test 2: every bad row is quarantined, with a reason
# ---------------------------------------------------------------------------


class TestQuarantine:

    def test_every_quarantined_row_has_a_reason(self, spark, lakehouse):
        run_pipeline(spark, lakehouse, Faults(
            negative_amounts=2, unknown_currencies=1, unmasked_pans=1))

        quarantine = read_quarantine(spark, lakehouse["quarantine"], "payments")

        assert quarantine.count() > 0
        assert quarantine.filter(f"{QUARANTINE_REASON} IS NULL").count() == 0
        assert quarantine.filter(f"{QUARANTINE_DETAIL} IS NULL").count() == 0

    def test_each_fault_lands_under_its_own_reason(self, spark, lakehouse):
        run_pipeline(spark, lakehouse, Faults(
            negative_amounts=2, unknown_currencies=1, unmasked_pans=1))

        quarantine = read_quarantine(spark, lakehouse["quarantine"], "payments")
        counts = {row[QUARANTINE_REASON]: row["count"]
                  for row in quarantine.groupBy(QUARANTINE_REASON).count().collect()}

        assert counts.get("NEGATIVE_AMOUNT") == 2
        assert counts.get("UNKNOWN_CURRENCY") == 1
        assert counts.get("UNMASKED_PAN") == 1

    def test_nothing_is_lost_between_bronze_and_silver(self, spark, lakehouse):
        """Clean + quarantined + duplicates removed must account for every row.

        A row that is neither kept nor explained has been silently dropped, and
        that is the failure mode quarantine exists to prevent.
        """
        _, results = run_pipeline(spark, lakehouse, Faults(
            negative_amounts=2, unknown_currencies=1, duplicate_payments=3))

        result = results["payments"]
        assert result.rows_in == result.rows_clean + result.rows_quarantined + result.duplicates_removed

    def test_a_clean_batch_quarantines_nothing(self, spark, lakehouse):
        _, results = run_pipeline(spark, lakehouse, datasets=ALL_DATASETS)

        for dataset, result in results.items():
            if result.rows_quarantined == 0:
                continue

            # Name the offending rule and show a row. "A rule is too strict" is
            # useless when the failure only reproduces on another platform.
            rows = read_quarantine(spark, lakehouse["quarantine"], dataset)
            reasons = {r[QUARANTINE_REASON]: r["count"]
                       for r in rows.groupBy(QUARANTINE_REASON).count().collect()}
            sample = rows.limit(1).collect()[0].asDict()

            populated = {k: v for k, v in sample.items() if v not in (None, "")}
            raise AssertionError(
                f"{dataset} quarantined {result.rows_quarantined} rows from a clean batch. "
                f"reasons={reasons} sample={populated}")

    def test_quarantined_rows_keep_their_lineage(self, spark, lakehouse):
        written, _ = run_pipeline(spark, lakehouse, Faults(negative_amounts=1))
        quarantine = read_quarantine(spark, lakehouse["quarantine"], "payments")

        row = quarantine.first()
        assert row["source_file"] == written["payments"].name
        assert row["ingested_at"] == INGESTED_AT.isoformat()
        assert row["_quarantined_at"] == PROCESSED_AT.isoformat()

    def test_a_truncated_row_is_quarantined_not_crashed_on(self, spark, lakehouse):
        run_pipeline(spark, lakehouse, Faults(truncate_last_row=True))

        quarantine = read_quarantine(spark, lakehouse["quarantine"], "payments")
        reasons = {row[QUARANTINE_REASON] for row in quarantine.collect()}

        assert quarantine.count() >= 1
        assert reasons <= {"MALFORMED_ROW", "MISSING_REQUIRED_FIELD", "UNMASKED_PAN",
                           "UNKNOWN_CURRENCY", "MALFORMED_AMOUNT", "UNKNOWN_STATUS"}


# ---------------------------------------------------------------------------
# Required test 3: no raw PAN survives into Silver
# ---------------------------------------------------------------------------


class TestCardDataIsContained:

    def test_no_raw_pan_in_silver(self, spark, lakehouse):
        run_pipeline(spark, lakehouse, Faults(unmasked_pans=3))

        silver = read_silver(spark, lakehouse["silver"], "payments")
        for row in silver.select("pan_masked").collect():
            assert re.fullmatch(r"\d{6}\*{4,9}\d{4}", row["pan_masked"]), row["pan_masked"]

    def test_no_raw_pan_in_quarantine_either(self, spark, lakehouse):
        """Quarantine must contain the leak, not relocate it.

        The row is here precisely because a full card number arrived. Writing it
        verbatim would move the PAN somewhere with less scrutiny and call the
        job done.
        """
        run_pipeline(spark, lakehouse, Faults(unmasked_pans=3))

        quarantine = read_quarantine(spark, lakehouse["quarantine"], "payments")
        unmasked = quarantine.filter(f"{QUARANTINE_REASON} = 'UNMASKED_PAN'")

        assert unmasked.count() == 3
        for row in unmasked.select("pan_masked").collect():
            assert row["pan_masked"] == "***REDACTED***"

    def test_no_pan_shaped_string_anywhere_in_silver(self, spark, lakehouse):
        """A blunt sweep across every string column, not just the obvious one."""
        run_pipeline(spark, lakehouse, Faults(unmasked_pans=2))

        silver = read_silver(spark, lakehouse["silver"], "payments")
        string_columns = [name for name, dtype in silver.dtypes if dtype == "string"]

        for row in silver.select(*string_columns).collect():
            for column in string_columns:
                value = row[column]
                if value and column != "card_token":
                    assert not RAW_PAN.fullmatch(value), f"{column} looks like a PAN: {value}"


# ---------------------------------------------------------------------------
# Typing, and the one defect that must never be quarantined
# ---------------------------------------------------------------------------


class TestConforming:

    def test_types_are_real_after_silver(self, spark, lakehouse):
        run_pipeline(spark, lakehouse)
        types = dict(read_silver(spark, lakehouse["silver"], "payments").dtypes)

        assert types["amount"] == "decimal(18,4)"
        assert types["created_at"] == "timestamp"
        assert types["business_date"] == "date"
        assert types["risk_score"] == "int"
        # Identifiers stay strings: they are opaque handles, not values.
        assert types["payment_id"] == "string"

    def test_money_survives_the_cast_exactly(self, spark, lakehouse):
        written, _ = run_pipeline(spark, lakehouse)

        import csv
        with written["payments"].open(encoding="utf-8", newline="") as handle:
            source = {row["payment_id"]: Decimal(row["amount"]) for row in csv.DictReader(handle)}

        silver = read_silver(spark, lakehouse["silver"], "payments")
        for row in silver.select("payment_id", "amount").collect():
            assert row["amount"] == source[row["payment_id"]]

    def test_an_unbalanced_journal_fails_the_pipeline(self, spark, lakehouse):
        """Never quarantined. docs/data-contracts.md section 9 is explicit.

        Every other defect affects some rows; this one means money was created
        or destroyed, and a pipeline that quarantined it would report success.
        """
        generate(config(faults=Faults(unbalanced_journals=2)), lakehouse["landing"])
        ingest(spark, "ledger_entries", lakehouse["landing"], lakehouse["bronze"], INGESTED_AT)

        with pytest.raises(UnbalancedLedger, match="do not balance"):
            build(spark, "ledger_entries", lakehouse["bronze"], lakehouse["silver"],
                  lakehouse["quarantine"], PROCESSED_AT)

    def test_balanced_ledger_passes(self, spark, lakehouse):
        _, results = run_pipeline(spark, lakehouse, datasets=("ledger_entries",))

        assert results["ledger_entries"].rows_clean > 0
        assert results["ledger_entries"].rows_quarantined == 0


# ---------------------------------------------------------------------------
# The transformation is a pure function
# ---------------------------------------------------------------------------


class TestPurity:

    def test_to_silver_needs_no_paths(self, spark, lakehouse):
        """The rules run against any DataFrame, which is what makes them
        portable to Databricks unchanged in Phase 5."""
        run_pipeline(spark, lakehouse, Faults(negative_amounts=1))

        bronze = read_bronze(spark, lakehouse["bronze"], "payments")
        clean, quarantined = to_silver(bronze, "payments")

        assert clean.count() + quarantined.count() <= bronze.count()
        assert quarantined.filter(f"{QUARANTINE_REASON} = 'NEGATIVE_AMOUNT'").count() == 1

    def test_deduplicate_is_a_plain_dataframe_function(self, spark, lakehouse):
        run_pipeline(spark, lakehouse, Faults(duplicate_payments=2))

        bronze = read_bronze(spark, lakehouse["bronze"], "payments")
        assert deduplicate(bronze, "payments").count() == bronze.count() - 2
