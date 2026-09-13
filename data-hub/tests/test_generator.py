"""Phase 2.1: the generator produces contract-conformant, reproducible data."""

from __future__ import annotations

import csv
import re
from datetime import date
from decimal import Decimal

import pytest

from generator import COLUMNS, Faults, GeneratorConfig, build, file_name, generate
from generator.datasets import CLEARED_STATUSES
from generator.ids import uuid7, variant_of, version_of
from generator.money import split_fees

BUSINESS_DATE = date(2026, 9, 14)


def config(**overrides) -> GeneratorConfig:
    defaults = dict(business_date=BUSINESS_DATE, seed=42, payment_count=120)
    return GeneratorConfig(**{**defaults, **overrides})


def read_csv(path):
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


# ---------------------------------------------------------------------------
# Determinism — the Phase 2.1 definition of done
# ---------------------------------------------------------------------------


class TestDeterminism:

    def test_same_seed_produces_byte_identical_files(self, tmp_path):
        first = generate(config(), tmp_path / "run1")
        second = generate(config(), tmp_path / "run2")

        for dataset in COLUMNS:
            assert first[dataset].read_bytes() == second[dataset].read_bytes(), (
                f"{dataset} differed between two runs with the same seed"
            )

    def test_different_seed_produces_different_data(self, tmp_path):
        first = generate(config(seed=1), tmp_path / "a")
        second = generate(config(seed=2), tmp_path / "b")

        assert first["payments"].read_bytes() != second["payments"].read_bytes()

    def test_generation_does_not_read_the_wall_clock(self, tmp_path):
        """Two runs separated in time must still agree.

        Anything reading ``datetime.now()`` would pass a single run and fail
        here — which is the whole reason timestamps derive from business_date.
        """
        import time

        first = generate(config(), tmp_path / "before")
        time.sleep(1.1)
        second = generate(config(), tmp_path / "after")

        assert first["payments"].read_bytes() == second["payments"].read_bytes()


# ---------------------------------------------------------------------------
# The file contract
# ---------------------------------------------------------------------------


class TestFileContract:

    def test_file_names_follow_the_convention(self, tmp_path):
        written = generate(config(batch_sequence=7), tmp_path)

        assert written["payments"].name == "payments_2026-09-14_B20260914007_v1.csv"
        assert re.fullmatch(
            r"[a-z_]+_\d{4}-\d{2}-\d{2}_B\d{8}\d{3}_v\d+\.csv", written["clearing"].name)

    def test_columns_match_the_contract_exactly(self, tmp_path):
        written = generate(config(), tmp_path)

        for dataset, expected in COLUMNS.items():
            with written[dataset].open(encoding="utf-8", newline="") as handle:
                header = next(csv.reader(handle))
            assert tuple(header) == expected, f"{dataset} header drifted from the contract"

    def test_line_endings_are_lf_and_there_is_no_bom(self, tmp_path):
        written = generate(config(), tmp_path)
        raw = written["payments"].read_bytes()

        assert b"\r\n" not in raw, "contract requires LF, not CRLF"
        assert not raw.startswith(b"\xef\xbb\xbf"), "contract requires UTF-8 without a BOM"

    def test_jpy_amounts_are_integral(self, tmp_path):
        written = generate(config(), tmp_path)

        for row in read_csv(written["payments"]):
            assert "." not in row["amount"], f"JPY amount carried minor units: {row['amount']}"

    def test_timestamps_are_utc_with_second_precision(self, tmp_path):
        written = generate(config(), tmp_path)
        pattern = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")

        for row in read_csv(written["payments"]):
            assert pattern.fullmatch(row["created_at"])

    def test_nulls_are_empty_fields(self, tmp_path):
        written = generate(config(), tmp_path)

        for row in read_csv(written["payments"]):
            for value in row.values():
                assert value not in ("NULL", "null", r"\N", "-", "None")

    def test_identifiers_are_uuid_v7(self, tmp_path):
        written = generate(config(), tmp_path)

        for row in read_csv(written["payments"])[:20]:
            assert version_of(row["payment_id"]) == 7
            assert variant_of(row["payment_id"]) == 0b10

    def test_uuid7_values_sort_by_time(self):
        import random

        rng = random.Random(7)
        generated = [uuid7(rng, 1_789_000_000_000 + i) for i in range(200)]

        assert generated == sorted(generated)


# ---------------------------------------------------------------------------
# Invariants the pipeline will later be asked to prove
# ---------------------------------------------------------------------------


class TestGeneratedDataIsInternallyConsistent:

    def test_every_journal_balances(self, tmp_path):
        written = generate(config(), tmp_path)

        journals: dict[str, Decimal] = {}
        for row in read_csv(written["ledger_entries"]):
            signed = Decimal(row["amount"])
            journals[row["journal_id"]] = journals.get(row["journal_id"], Decimal(0)) + (
                signed if row["direction"] == "DEBIT" else -signed)

        unbalanced = {j: total for j, total in journals.items() if total != 0}
        assert not unbalanced, f"unbalanced journals: {unbalanced}"

    def test_settlement_sums_to_zero(self, tmp_path):
        """Money is conserved: every yen one participant pays, another receives."""
        written = generate(config(), tmp_path)

        total = sum(Decimal(row["net_amount"]) for row in read_csv(written["settlement"]))
        assert total == 0, f"settlement does not conserve money: net total {total}"

    def test_the_network_settles_as_a_participant(self, tmp_path):
        written = generate(config(), tmp_path)
        types = {row["participant_type"] for row in read_csv(written["settlement"])}

        # Without NETWORK on the file the scheme fee has nowhere to go and the
        # conservation check above cannot hold.
        assert types == {"ISSUER", "ACQUIRER", "NETWORK"}

    def test_clearing_fee_components_sum_to_gross(self, tmp_path):
        written = generate(config(), tmp_path)

        for row in read_csv(written["clearing"]):
            gross = Decimal(row["gross_amount"])
            components = (
                Decimal(row["interchange_fee"])
                + Decimal(row["scheme_fee"])
                + Decimal(row["acquirer_margin"])
                + Decimal(row["net_to_merchant"])
            )
            assert components == gross, f"fees do not reconcile for {row['payment_id']}"

    def test_every_clearing_row_has_a_cleared_or_settled_payment(self, tmp_path):
        written = generate(config(), tmp_path)

        payments = {row["payment_id"]: row["status"] for row in read_csv(written["payments"])}
        for row in read_csv(written["clearing"]):
            assert payments[row["payment_id"]] in CLEARED_STATUSES

    def test_payment_amount_relationships_hold(self, tmp_path):
        written = generate(config(), tmp_path)

        for row in read_csv(written["payments"]):
            if row["authorized_amount"]:
                assert Decimal(row["authorized_amount"]) <= Decimal(row["amount"])
            if row["captured_amount"]:
                assert Decimal(row["captured_amount"]) <= Decimal(row["authorized_amount"])
            if Decimal(row["refunded_amount"]) > 0:
                assert Decimal(row["refunded_amount"]) <= Decimal(row["captured_amount"])

    def test_declines_carry_a_reason(self, tmp_path):
        written = generate(config(), tmp_path)
        declined = [r for r in read_csv(written["payments"]) if r["status"] == "DECLINED"]

        assert declined, "expected the generator to produce some declines"
        for row in declined:
            assert row["decline_reason"]

    def test_no_raw_pan_in_a_clean_run(self, tmp_path):
        written = generate(config(), tmp_path)

        for row in read_csv(written["payments"]):
            assert re.fullmatch(r"\d{6}\*{6}\d{4}", row["pan_masked"])


class TestFeeArithmetic:

    def test_the_reference_dinner_splits_as_documented(self):
        split = split_fees(Decimal(5000), "JPY",
                           Decimal("0.0330"), Decimal("0.0200"), Decimal("0.0030"))

        assert split.merchant_discount == 165
        assert split.interchange == 100
        assert split.scheme_fee == 15
        assert split.acquirer_margin == 50
        assert split.net_to_merchant == 4835

    @pytest.mark.parametrize("gross", [1, 7, 333, 555, 999, 5000, 123_456])
    def test_components_always_reconcile(self, gross):
        """The residual margin guarantees this for every amount.

        ¥555 is the case that breaks if the margin is computed from its own
        percentage: the four rounded components then total ¥19 against a ¥18
        merchant discount.
        """
        split = split_fees(Decimal(gross), "JPY",
                           Decimal("0.0330"), Decimal("0.0200"), Decimal("0.0030"))

        assert split.interchange + split.scheme_fee + split.acquirer_margin == split.merchant_discount
        assert split.net_to_merchant + split.merchant_discount == split.gross


# ---------------------------------------------------------------------------
# Deliberate defects
# ---------------------------------------------------------------------------


class TestFaultInjection:

    def test_duplicate_payment_ids(self, tmp_path):
        written = generate(config(faults=Faults(duplicate_payments=3)), tmp_path)
        rows = read_csv(written["payments"])

        ids = [row["payment_id"] for row in rows]
        assert len(ids) - len(set(ids)) == 3

    def test_negative_amounts(self, tmp_path):
        written = generate(config(faults=Faults(negative_amounts=2)), tmp_path)
        negatives = [r for r in read_csv(written["payments"]) if Decimal(r["amount"]) < 0]

        assert len(negatives) == 2

    def test_unknown_currency(self, tmp_path):
        written = generate(config(faults=Faults(unknown_currencies=1)), tmp_path)
        bad = [r for r in read_csv(written["payments"]) if r["currency"] == "ZZZ"]

        assert len(bad) == 1

    def test_unmasked_pan(self, tmp_path):
        written = generate(config(faults=Faults(unmasked_pans=1)), tmp_path)
        raw = [r for r in read_csv(written["payments"]) if "*" not in r["pan_masked"]]

        assert len(raw) == 1
        assert raw[0]["pan_masked"] == "4111111111111111"

    def test_truncated_final_row(self, tmp_path):
        written = generate(config(faults=Faults(truncate_last_row=True)), tmp_path)
        text = written["payments"].read_text(encoding="utf-8")

        assert not text.endswith("\n"), "a truncated file should not end cleanly"
        clean = generate(config(), tmp_path / "clean")["payments"].read_text(encoding="utf-8")
        assert len(text) < len(clean)

    def test_extra_unexpected_column(self, tmp_path):
        written = generate(config(faults=Faults(extra_column=True)), tmp_path)
        with written["payments"].open(encoding="utf-8", newline="") as handle:
            header = next(csv.reader(handle))

        assert header[-1] == "unexpected_column"
        assert len(header) == len(COLUMNS["payments"]) + 1

    def test_unbalanced_journal(self, tmp_path):
        written = generate(config(faults=Faults(unbalanced_journals=2)), tmp_path)

        journals: dict[str, Decimal] = {}
        for row in read_csv(written["ledger_entries"]):
            amount = Decimal(row["amount"])
            journals[row["journal_id"]] = journals.get(row["journal_id"], Decimal(0)) + (
                amount if row["direction"] == "DEBIT" else -amount)

        assert len([t for t in journals.values() if t != 0]) == 2

    def test_missing_settlement_lines(self, tmp_path):
        complete = read_csv(generate(config(), tmp_path / "complete")["settlement"])
        partial = read_csv(
            generate(config(faults=Faults(missing_settlement_lines=1)), tmp_path / "partial")["settlement"])

        assert len(partial) == len(complete) - 1
        # The conservation check must now fail — that is the point of the fault.
        assert sum(Decimal(r["net_amount"]) for r in partial) != 0

    def test_late_file_carries_the_previous_business_date(self, tmp_path):
        written = generate(config(faults=Faults(late_file=True)), tmp_path)

        assert "2026-09-13" in written["payments"].name
        assert "B20260914001" in written["payments"].name, (
            "the batch id still says the 14th — that mismatch is what Bronze must flag")

    def test_a_clean_run_injects_nothing(self, tmp_path):
        assert not Faults().any_enabled()

        written = generate(config(), tmp_path)
        rows = read_csv(written["payments"])

        ids = [row["payment_id"] for row in rows]
        assert len(ids) == len(set(ids))
        assert all(Decimal(row["amount"]) > 0 for row in rows)
        assert all(row["currency"] == "JPY" for row in rows)


def test_file_name_helper_matches_written_file(tmp_path):
    cfg = config(batch_sequence=42)
    written = generate(cfg, tmp_path)

    assert written["settlement"].name == file_name("settlement", cfg)


def test_build_is_pure(tmp_path):
    """Building twice yields equal rows; nothing is carried between calls."""
    cfg = config()

    assert build(cfg).payments == build(cfg).payments
