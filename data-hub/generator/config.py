"""Configuration for a generation run."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

SCHEMA_VERSION = 1

#: Obvious test PANs. CLAUDE.md: synthetic data only, never a real card number.
TEST_PAN_PREFIXES = ("411111", "555555", "353011", "378282")

CARD_SCHEMES = ("VISA", "MASTERCARD", "JCB", "AMEX")

#: Merchant category codes: restaurants, groceries, transport, hotels, books.
MCCS = ("5812", "5411", "4111", "7011", "5942")


@dataclass(frozen=True)
class Faults:
    """Deliberate defects to inject.

    Each corresponds to a row of the failure-mode table in
    ``docs/data-contracts.md`` section 9. They exist so the pipeline can be
    proven to survive them rather than merely assumed to.

    Everything defaults to zero, so a plain run produces clean files.
    """

    #: Emit N payment rows a second time with the same payment_id.
    duplicate_payments: int = 0
    #: Emit N payment rows whose amount is negative.
    negative_amounts: int = 0
    #: Emit N payment rows with a currency code that is not ISO 4217.
    unknown_currencies: int = 0
    #: Emit N payment rows whose pan_masked holds a full PAN.
    unmasked_pans: int = 0
    #: Truncate the final row of the payments file mid-field.
    truncate_last_row: bool = False
    #: Append a column the contract does not define.
    extra_column: bool = False
    #: Post N journals whose debits do not equal their credits.
    unbalanced_journals: int = 0
    #: Omit N participants from the settlement file.
    missing_settlement_lines: int = 0
    #: Stamp the files with a business date one day before the batch id implies.
    late_file: bool = False

    def any_enabled(self) -> bool:
        return (
            self.duplicate_payments
            or self.negative_amounts
            or self.unknown_currencies
            or self.unmasked_pans
            or self.truncate_last_row
            or self.extra_column
            or self.unbalanced_journals
            or self.missing_settlement_lines
            or self.late_file
        )


@dataclass(frozen=True)
class GeneratorConfig:
    """Everything that determines the output.

    Given the same config, the generator must produce byte-identical files. That
    is why there is a ``seed`` and no reference to the wall clock anywhere: every
    timestamp is derived from ``business_date``.
    """

    business_date: date
    seed: int = 42
    batch_sequence: int = 1

    payment_count: int = 100
    issuer_count: int = 3
    acquirer_count: int = 2

    currency: str = "JPY"
    min_amount: int = 100
    max_amount: int = 200_000

    #: Fractions, matching nexuspay.fees in payment-core's application.yml.
    merchant_discount_rate: Decimal = Decimal("0.0330")
    interchange_rate: Decimal = Decimal("0.0200")
    scheme_fee_rate: Decimal = Decimal("0.0030")

    faults: Faults = field(default_factory=Faults)

    @property
    def batch_id(self) -> str:
        """``B`` + YYYYMMDD + a three-digit sequence, per the contract."""
        return f"B{self.business_date:%Y%m%d}{self.batch_sequence:03d}"

    @property
    def file_business_date(self) -> date:
        """The date written into file names and rows.

        A late file carries the previous day's business date while arriving in
        this batch, which is exactly the mismatch Bronze must notice.
        """
        if self.faults.late_file:
            from datetime import timedelta

            return self.business_date - timedelta(days=1)
        return self.business_date
