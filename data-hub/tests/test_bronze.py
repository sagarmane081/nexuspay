"""Phase 2.2: Bronze is a faithful, append-only record of what arrived."""

from __future__ import annotations

import csv
from datetime import date, datetime, timezone

import pytest

from generator import COLUMNS, Faults, GeneratorConfig, generate
from pipelines.bronze import (
    ContractViolation,
    bronze_schema,
    discover,
    ingest,
    ingest_all,
    parse_file_name,
    read_bronze,
)

BUSINESS_DATE = date(2026, 9, 14)
INGESTED_AT = datetime(2026, 9, 15, 3, 0, tzinfo=timezone.utc)


def config(**overrides) -> GeneratorConfig:
    defaults = dict(business_date=BUSINESS_DATE, seed=42, payment_count=60)
    return GeneratorConfig(**{**defaults, **overrides})


def source_row_count(path) -> int:
    """Rows in the CSV, excluding the header — the number Bronze must match."""
    with path.open(encoding="utf-8", newline="") as handle:
        return sum(1 for _ in csv.DictReader(handle))


@pytest.fixture
def landing(tmp_path):
    """A landing zone holding one clean batch."""
    directory = tmp_path / "landing"
    written = generate(config(), directory)
    return directory, written


# ---------------------------------------------------------------------------
# Row-count parity — the first Phase 2.2 test
# ---------------------------------------------------------------------------


class TestIngestion:

    def test_bronze_row_count_equals_the_source_files(self, spark, landing, tmp_path):
        landing_dir, written = landing
        bronze = tmp_path / "bronze"

        results = ingest_all(spark, landing_dir, bronze, INGESTED_AT)

        for dataset in COLUMNS:
            expected = source_row_count(written[dataset])
            actual = read_bronze(spark, bronze, dataset).count()

            assert actual == expected, f"{dataset}: Bronze has {actual} rows, source had {expected}"
            assert results[dataset].rows_ingested == expected

    def test_every_contract_column_survives_ingestion(self, spark, landing, tmp_path):
        landing_dir, _ = landing
        bronze = tmp_path / "bronze"
        ingest_all(spark, landing_dir, bronze, INGESTED_AT)

        for dataset, expected in COLUMNS.items():
            columns = read_bronze(spark, bronze, dataset).columns
            for name in expected:
                assert name in columns, f"{dataset} lost column {name}"

    def test_lineage_columns_are_stamped(self, spark, landing, tmp_path):
        landing_dir, written = landing
        bronze = tmp_path / "bronze"
        ingest(spark, "payments", landing_dir, bronze, INGESTED_AT)

        frame = read_bronze(spark, bronze, "payments")
        assert {"source_file", "ingested_at", "_rescued_data"} <= set(frame.columns)

        row = frame.select("source_file", "ingested_at").first()
        assert row["source_file"] == written["payments"].name
        assert row["ingested_at"] == INGESTED_AT.isoformat()

    def test_everything_is_stored_as_a_string(self, spark, landing, tmp_path):
        """Bronze casts nothing.

        A row with a negative amount or an unknown currency must survive intact
        so Silver can quarantine it with a reason. Casting here would turn it
        into a null, and the only remaining evidence would be a count that does
        not add up.
        """
        landing_dir, _ = landing
        bronze = tmp_path / "bronze"
        ingest(spark, "payments", landing_dir, bronze, INGESTED_AT)

        types = dict(read_bronze(spark, bronze, "payments").dtypes)
        for name in COLUMNS["payments"]:
            assert types[name] == "string", f"{name} was cast to {types[name]}"

    def test_bad_rows_are_preserved_rather_than_dropped(self, spark, tmp_path):
        landing_dir = tmp_path / "landing"
        written = generate(
            config(faults=Faults(negative_amounts=2, unknown_currencies=1, unmasked_pans=1)),
            landing_dir)
        bronze = tmp_path / "bronze"

        ingest(spark, "payments", landing_dir, bronze, INGESTED_AT)
        frame = read_bronze(spark, bronze, "payments")

        assert frame.count() == source_row_count(written["payments"])
        assert frame.filter("amount LIKE '-%'").count() == 2
        assert frame.filter("currency = 'ZZZ'").count() == 1
        assert frame.filter("pan_masked NOT LIKE '%*%'").count() == 1


# ---------------------------------------------------------------------------
# Idempotency — the second Phase 2.2 test
# ---------------------------------------------------------------------------


class TestReIngestionIsANoop:

    def test_ingesting_the_same_file_twice_adds_nothing(self, spark, landing, tmp_path):
        landing_dir, written = landing
        bronze = tmp_path / "bronze"

        first = ingest(spark, "payments", landing_dir, bronze, INGESTED_AT)
        after_first = read_bronze(spark, bronze, "payments").count()

        second = ingest(spark, "payments", landing_dir, bronze, INGESTED_AT)
        after_second = read_bronze(spark, bronze, "payments").count()

        assert first.rows_ingested == source_row_count(written["payments"])
        assert second.was_noop
        assert second.files_skipped == [written["payments"].name]
        assert second.rows_ingested == 0
        assert after_second == after_first, "a replayed file doubled the data"

    def test_a_third_run_still_changes_nothing(self, spark, landing, tmp_path):
        landing_dir, _ = landing
        bronze = tmp_path / "bronze"

        ingest(spark, "payments", landing_dir, bronze, INGESTED_AT)
        baseline = read_bronze(spark, bronze, "payments").count()

        for _ in range(2):
            ingest(spark, "payments", landing_dir, bronze, INGESTED_AT)

        assert read_bronze(spark, bronze, "payments").count() == baseline

    def test_a_new_batch_is_appended_alongside_the_old(self, spark, tmp_path):
        landing_dir = tmp_path / "landing"
        bronze = tmp_path / "bronze"

        first = generate(config(batch_sequence=1), landing_dir)
        ingest(spark, "payments", landing_dir, bronze, INGESTED_AT)

        # A genuinely different batch, not a replay.
        second = generate(config(batch_sequence=2, seed=99), landing_dir)
        result = ingest(spark, "payments", landing_dir, bronze, INGESTED_AT)

        assert result.files_ingested == [second["payments"].name]
        assert result.files_skipped == [first["payments"].name]

        total = source_row_count(first["payments"]) + source_row_count(second["payments"])
        assert read_bronze(spark, bronze, "payments").count() == total

    def test_each_row_traces_back_to_its_own_file(self, spark, tmp_path):
        landing_dir = tmp_path / "landing"
        bronze = tmp_path / "bronze"

        first = generate(config(batch_sequence=1), landing_dir)
        second = generate(config(batch_sequence=2, seed=99), landing_dir)
        ingest(spark, "payments", landing_dir, bronze, INGESTED_AT)

        frame = read_bronze(spark, bronze, "payments")
        for written in (first, second):
            name = written["payments"].name
            assert frame.filter(frame.source_file == name).count() == source_row_count(written["payments"])


# ---------------------------------------------------------------------------
# The file name is a fact, not a hint
# ---------------------------------------------------------------------------


class TestFileNameContract:

    def test_file_name_is_parsed(self):
        parsed = parse_file_name("payments_2026-09-14_B20260914001_v1.csv")

        assert parsed == {
            "dataset": "payments",
            "business_date": "2026-09-14",
            "batch_id": "B20260914001",
            "schema_version": "1",
        }

    @pytest.mark.parametrize("name", [
        "payments.csv",
        "payments_2026-09-14.csv",
        "payments_2026-09-14_B20260914001.csv",
        "payments_20260914_B20260914001_v1.csv",
    ])
    def test_malformed_file_names_are_refused(self, name):
        with pytest.raises(ContractViolation):
            parse_file_name(name)

    def test_discover_ignores_files_that_do_not_match(self, spark, landing, tmp_path):
        landing_dir, written = landing
        (landing_dir / "payments" / "notes.txt").write_text("not a data file")
        (landing_dir / "payments" / "payments_backup.csv").write_text("nor is this")

        found = discover(landing_dir, "payments")
        assert [p.name for p in found] == [written["payments"].name]

    def test_a_file_whose_name_disagrees_with_its_rows_is_refused(self, spark, landing, tmp_path):
        """The mismatch two batches concatenated into one file would produce."""
        landing_dir, written = landing
        payments = written["payments"]

        mislabelled = payments.with_name("payments_2026-09-14_B20260914999_v1.csv")
        payments.rename(mislabelled)

        with pytest.raises(ContractViolation, match="B20260914999"):
            ingest(spark, "payments", landing_dir, tmp_path / "bronze", INGESTED_AT)

    def test_nothing_is_written_when_the_contract_is_violated(self, spark, landing, tmp_path):
        landing_dir, written = landing
        bronze = tmp_path / "bronze"
        written["payments"].rename(
            written["payments"].with_name("payments_2026-09-14_B20260914999_v1.csv"))

        with pytest.raises(ContractViolation):
            ingest(spark, "payments", landing_dir, bronze, INGESTED_AT)

        assert not (bronze / "payments" / "_delta_log").exists(), (
            "a rejected file must leave no partial table behind")


# ---------------------------------------------------------------------------
# Delta behaviour
# ---------------------------------------------------------------------------


class TestDeltaTable:

    def test_the_table_is_delta_with_a_transaction_log(self, spark, landing, tmp_path):
        landing_dir, _ = landing
        bronze = tmp_path / "bronze"
        ingest(spark, "payments", landing_dir, bronze, INGESTED_AT)

        assert (bronze / "payments" / "_delta_log").is_dir()

        history = spark.sql(f"DESCRIBE HISTORY delta.`{(bronze / 'payments').as_posix()}`")
        assert history.count() == 1
        assert history.select("operation").first()["operation"] == "WRITE"

    def test_each_batch_adds_one_version_to_the_log(self, spark, tmp_path):
        landing_dir = tmp_path / "landing"
        bronze = tmp_path / "bronze"

        generate(config(batch_sequence=1), landing_dir)
        ingest(spark, "payments", landing_dir, bronze, INGESTED_AT)
        generate(config(batch_sequence=2, seed=99), landing_dir)
        ingest(spark, "payments", landing_dir, bronze, INGESTED_AT)

        history = spark.sql(f"DESCRIBE HISTORY delta.`{(bronze / 'payments').as_posix()}`")
        assert history.count() == 2

        # Append-only: no overwrite ever appears in the history.
        operations = {row["operation"] for row in history.select("operation").collect()}
        assert operations == {"WRITE"}

    def test_bronze_schema_is_all_strings_plus_the_rescue_column(self):
        schema = bronze_schema("clearing")

        assert [field.name for field in schema.fields][:-1] == list(COLUMNS["clearing"])
        assert schema.fields[-1].name == "_rescued_data"
        assert {str(field.dataType) for field in schema.fields} == {"StringType()"}
