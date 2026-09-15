"""Phase 2.4: the medallion pipeline neither loses nor creates money.

This is the suite the data hub exists to justify. Three properties, checked end
to end over a real pipeline run rather than over mocks:

1. **Counts and JPY sums reconcile** across Source, Bronze, Silver and Gold.
2. **Debits equal credits** in every layer.
3. **Every Gold row traces back** to a ``batch_id`` and its source files.

The pipeline runs once for the whole module. Each test then interrogates the
result, which keeps a Spark-heavy suite tractable without weakening any
assertion — every test still reads real Delta tables produced by real jobs.
"""

from __future__ import annotations

import csv
from datetime import date, datetime, timezone
from decimal import Decimal

import pytest
from pyspark.sql import functions as F

from generator import COLUMNS, Faults, GeneratorConfig, generate
from pipelines.bronze import ingest_all, read_bronze
from pipelines.gold import LINEAGE_COLUMNS, MARTS, build_all as build_gold, read_gold
from pipelines.silver import build_all as build_silver, read_quarantine, read_silver

BUSINESS_DATE = date(2026, 9, 14)
INGESTED_AT = datetime(2026, 9, 15, 3, 0, tzinfo=timezone.utc)
PROCESSED_AT = datetime(2026, 9, 15, 4, 0, tzinfo=timezone.utc)


def total(frame, column) -> Decimal:
    """Sum a money column as an exact Decimal, never a float."""
    value = frame.selectExpr(f"cast(sum(cast({column} as decimal(18,4))) as string)").first()[0]
    return Decimal(value) if value is not None else Decimal(0)


def csv_rows(path) -> list[dict]:
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


@pytest.fixture(scope="module")
def pipeline(spark, tmp_path_factory):
    """One clean end-to-end run: generate, Bronze, Silver, Gold."""
    root = tmp_path_factory.mktemp("lakehouse")
    paths = {name: root / name for name in ("landing", "bronze", "silver", "quarantine", "gold")}

    written = generate(
        GeneratorConfig(business_date=BUSINESS_DATE, seed=42, payment_count=80),
        paths["landing"])

    ingest_all(spark, paths["landing"], paths["bronze"], INGESTED_AT)
    silver = build_silver(spark, paths["bronze"], paths["silver"], paths["quarantine"], PROCESSED_AT)
    gold = build_gold(spark, paths["bronze"], paths["silver"], paths["quarantine"], paths["gold"])

    return {"paths": paths, "written": written, "silver": silver, "gold": gold}


# ---------------------------------------------------------------------------
# 1. Counts and sums reconcile across every layer
# ---------------------------------------------------------------------------


class TestCountReconciliation:

    @pytest.mark.parametrize("dataset", list(COLUMNS))
    def test_source_rows_equal_bronze_rows(self, spark, pipeline, dataset):
        expected = len(csv_rows(pipeline["written"][dataset]))
        actual = read_bronze(spark, pipeline["paths"]["bronze"], dataset).count()

        assert actual == expected, f"{dataset}: Bronze {actual} vs source {expected}"

    @pytest.mark.parametrize("dataset", list(COLUMNS))
    def test_every_bronze_row_is_accounted_for(self, spark, pipeline, dataset):
        """Kept, quarantined, or deduplicated — never silently dropped.

        A row that reached Bronze and appears nowhere afterwards is the failure
        mode quarantine exists to prevent, and the only trace would be a count
        that does not add up.
        """
        result = pipeline["silver"][dataset]

        assert result.rows_in == (
            result.rows_clean + result.rows_quarantined + result.duplicates_removed)

    @pytest.mark.parametrize("dataset", list(COLUMNS))
    def test_reconciliation_mart_reports_nothing_unaccounted(self, spark, pipeline, dataset):
        mart = read_gold(spark, pipeline["paths"]["gold"], "reconciliation")
        rows = mart.filter(mart.dataset == dataset).collect()

        assert rows, f"reconciliation mart has no row for {dataset}"
        for row in rows:
            # A clean batch has no duplicates, so nothing may be unaccounted.
            assert row["rows_unaccounted"] == 0, (
                f"{dataset} batch {row['batch_id']}: {row['rows_unaccounted']} rows vanished")


class TestSumReconciliation:

    def test_payment_amounts_survive_bronze_to_silver(self, spark, pipeline):
        bronze = read_bronze(spark, pipeline["paths"]["bronze"], "payments")
        silver = read_silver(spark, pipeline["paths"]["silver"], "payments")

        assert total(bronze, "amount") == total(silver, "amount")

    def test_source_sum_equals_silver_sum(self, spark, pipeline):
        source = sum(Decimal(row["amount"]) for row in csv_rows(pipeline["written"]["payments"]))
        silver = read_silver(spark, pipeline["paths"]["silver"], "payments")

        assert total(silver, "amount") == source

    def test_clearing_gross_survives_into_gold(self, spark, pipeline):
        """Aggregation must not change the total, only its shape."""
        silver = read_silver(spark, pipeline["paths"]["silver"], "clearing")
        gold = read_gold(spark, pipeline["paths"]["gold"], "merchant_revenue")

        assert total(gold, "gross_amount") == total(silver, "gross_amount")

    def test_every_fee_component_survives_into_gold(self, spark, pipeline):
        silver = read_silver(spark, pipeline["paths"]["silver"], "clearing")
        gold = read_gold(spark, pipeline["paths"]["gold"], "merchant_revenue")

        for column in ("interchange_fee", "scheme_fee", "acquirer_margin", "net_to_merchant"):
            assert total(gold, column) == total(silver, column), f"{column} changed during aggregation"

    def test_gold_fees_still_reconstruct_gross(self, spark, pipeline):
        """The Phase 0.2 fee rule, still true after aggregating thousands of rows."""
        gold = read_gold(spark, pipeline["paths"]["gold"], "merchant_revenue")

        components = (total(gold, "interchange_fee") + total(gold, "scheme_fee")
                      + total(gold, "acquirer_margin") + total(gold, "net_to_merchant"))

        assert components == total(gold, "gross_amount")

    def test_settlement_net_survives_into_gold(self, spark, pipeline):
        silver = read_silver(spark, pipeline["paths"]["silver"], "settlement")
        gold = read_gold(spark, pipeline["paths"]["gold"], "settlement_positions")

        assert total(gold, "net_amount") == total(silver, "net_amount")


# ---------------------------------------------------------------------------
# 2. Debits equal credits, in every layer
# ---------------------------------------------------------------------------


class TestLedgerBalances:

    def test_debits_equal_credits_in_bronze(self, spark, pipeline):
        """Bronze holds strings, so this casts — but the invariant must already
        hold on the raw data, before anything has been conformed."""
        bronze = read_bronze(spark, pipeline["paths"]["bronze"], "ledger_entries")

        debits = total(bronze.filter("direction = 'DEBIT'"), "amount")
        credits = total(bronze.filter("direction = 'CREDIT'"), "amount")

        assert debits == credits
        assert debits > 0, "expected the batch to contain postings"

    def test_debits_equal_credits_in_silver(self, spark, pipeline):
        silver = read_silver(spark, pipeline["paths"]["silver"], "ledger_entries")

        assert (total(silver.filter("direction = 'DEBIT'"), "amount")
                == total(silver.filter("direction = 'CREDIT'"), "amount"))

    def test_debits_equal_credits_in_gold(self, spark, pipeline):
        gold = read_gold(spark, pipeline["paths"]["gold"], "ledger_balance")

        assert total(gold, "debits") == total(gold, "credits")

    def test_every_individual_journal_balances(self, spark, pipeline):
        """Totals can agree while individual journals are wrong in opposite
        directions. Per-journal is the assertion that actually holds."""
        gold = read_gold(spark, pipeline["paths"]["gold"], "ledger_balance")

        unbalanced = gold.filter("imbalance != 0").collect()
        assert unbalanced == [], f"{len(unbalanced)} journals do not balance"

    def test_settlement_conserves_money(self, spark, pipeline):
        """Every yen one participant pays, another receives.

        Only holds because NexusPay settles as a NETWORK participant — without
        it the scheme fee has nowhere to go and the column sums to the fee total
        instead of zero.
        """
        gold = read_gold(spark, pipeline["paths"]["gold"], "settlement_positions")

        assert total(gold, "net_amount") == Decimal(0)
        assert {r["participant_type"] for r in gold.select("participant_type").distinct().collect()} == {
            "ISSUER", "ACQUIRER", "NETWORK"}


# ---------------------------------------------------------------------------
# 3. Every Gold row traces back to its source
# ---------------------------------------------------------------------------


class TestLineage:

    @pytest.mark.parametrize("mart", [m for m in MARTS if m != "reconciliation"])
    def test_every_gold_row_carries_batch_and_source_files(self, spark, pipeline, mart):
        frame = read_gold(spark, pipeline["paths"]["gold"], mart)

        for column in LINEAGE_COLUMNS:
            assert column in frame.columns, f"{mart} is missing {column}"

        assert frame.count() > 0
        assert frame.filter("batch_id IS NULL OR batch_id = ''").count() == 0
        assert frame.filter("source_files IS NULL OR size(source_files) = 0").count() == 0

    @pytest.mark.parametrize("mart", [m for m in MARTS if m != "reconciliation"])
    def test_named_source_files_actually_exist(self, spark, pipeline, mart):
        """Lineage that names a file nobody can find is decoration."""
        frame = read_gold(spark, pipeline["paths"]["gold"], mart)
        landing = pipeline["paths"]["landing"]

        on_disk = {path.name for path in landing.rglob("*.csv")}
        for row in frame.select("source_files").collect():
            for name in row["source_files"]:
                assert name in on_disk, f"{mart} cites {name}, which is not in the landing zone"

    def test_a_gold_figure_can_be_traced_back_to_source_rows(self, spark, pipeline):
        """The question an operator actually asks: this number looks wrong,
        where did it come from?"""
        gold = read_gold(spark, pipeline["paths"]["gold"], "merchant_revenue")
        row = gold.orderBy(F.col("gross_amount").desc()).first()

        silver = read_silver(spark, pipeline["paths"]["silver"], "clearing")
        contributing = silver.filter(
            (silver.merchant_id == row["merchant_id"])
            & (silver.business_date == row["business_date"])
            & (silver.batch_id == row["batch_id"]))

        assert contributing.count() == row["transaction_count"]
        assert total(contributing, "gross_amount") == Decimal(str(row["gross_amount"]))


# ---------------------------------------------------------------------------
# The checks must be capable of failing
# ---------------------------------------------------------------------------


class TestTheSuiteDetectsRealDamage:
    """A reconciliation suite that cannot fail proves nothing.

    Each test here runs a deliberately damaged batch and asserts the pipeline
    notices — the negative half of every claim made above.
    """

    def run_damaged(self, spark, tmp_path, faults: Faults):
        paths = {name: tmp_path / name for name in
                 ("landing", "bronze", "silver", "quarantine", "gold")}
        generate(GeneratorConfig(business_date=BUSINESS_DATE, seed=7,
                                 payment_count=40, faults=faults), paths["landing"])
        ingest_all(spark, paths["landing"], paths["bronze"], INGESTED_AT)
        build_silver(spark, paths["bronze"], paths["silver"], paths["quarantine"], PROCESSED_AT)
        build_gold(spark, paths["bronze"], paths["silver"], paths["quarantine"], paths["gold"])
        return paths

    def test_a_missing_settlement_line_breaks_conservation(self, spark, tmp_path):
        paths = self.run_damaged(spark, tmp_path, Faults(missing_settlement_lines=1))
        gold = read_gold(spark, paths["gold"], "settlement_positions")

        assert total(gold, "net_amount") != Decimal(0), (
            "dropping a participant must stop the ledger conserving money")

    def test_quarantined_rows_show_up_in_the_reconciliation_mart(self, spark, tmp_path):
        paths = self.run_damaged(
            spark, tmp_path, Faults(negative_amounts=2, unknown_currencies=1))
        mart = read_gold(spark, paths["gold"], "reconciliation")

        payments = mart.filter(mart.dataset == "payments").first()
        assert payments["quarantine_rows"] == 3
        assert payments["silver_rows"] + payments["quarantine_rows"] == payments["bronze_rows"]

    def test_deduplication_shows_up_as_unaccounted_rows(self, spark, tmp_path):
        """Duplicates are the one legitimate reason Bronze and Silver differ."""
        paths = self.run_damaged(spark, tmp_path, Faults(duplicate_payments=4))
        mart = read_gold(spark, paths["gold"], "reconciliation")

        payments = mart.filter(mart.dataset == "payments").first()
        assert payments["rows_unaccounted"] == 4
