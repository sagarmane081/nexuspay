"""Money arithmetic for generated data.

Mirrors ``business-requirements.md`` section 7 and the Java ``Money`` type. Two
rules matter, and both exist to stop the generator producing files that violate
the invariants the pipeline is supposed to prove:

1. **JPY has zero minor units.** Every amount is a whole yen.
2. **The acquirer's margin is the residual**, never a fourth percentage.
   Rounding four independent percentages does not reconcile — see the ¥555 case
   in ``MoneyTest`` on the Java side, where it conjures a yen from nothing.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP

#: Minor units per ISO 4217 currency we support.
MINOR_UNITS = {
    "JPY": 0,
    "USD": 2,
    "EUR": 2,
    "INR": 2,
}


def quantum(currency: str) -> Decimal:
    """The smallest representable amount, e.g. ``1`` for JPY, ``0.01`` for USD."""
    places = MINOR_UNITS[currency]
    return Decimal(1).scaleb(-places)


def round_money(amount: Decimal, currency: str) -> Decimal:
    """Round HALF_UP to the currency's minor units."""
    return amount.quantize(quantum(currency), rounding=ROUND_HALF_UP)


def format_money(amount: Decimal, currency: str) -> str:
    """Render per the contract: plain decimal, no symbol, no thousands separator.

    JPY renders as ``5000``, never ``5000.00`` — the contract calls JPY values
    integral, and a trailing ``.00`` would imply minor units that do not exist.
    """
    return str(round_money(amount, currency))


@dataclass(frozen=True)
class FeeSplit:
    """How one gross amount divides between the four parties."""

    gross: Decimal
    merchant_discount: Decimal
    interchange: Decimal
    scheme_fee: Decimal
    acquirer_margin: Decimal
    net_to_merchant: Decimal

    def __post_init__(self) -> None:
        components = self.interchange + self.scheme_fee + self.acquirer_margin
        if components != self.merchant_discount:
            raise AssertionError(
                f"fee components {components} do not sum to the merchant "
                f"discount {self.merchant_discount}"
            )
        if self.net_to_merchant != self.gross - self.merchant_discount:
            raise AssertionError("net to merchant does not reconcile with gross less MDR")


def split_fees(
    gross: Decimal,
    currency: str,
    merchant_discount_rate: Decimal,
    interchange_rate: Decimal,
    scheme_fee_rate: Decimal,
) -> FeeSplit:
    """Divide a gross amount into its fee components.

    The merchant discount, interchange and scheme fee are each rounded HALF_UP.
    The acquirer's margin is then whatever remains, which guarantees the parts
    sum exactly to the whole. Computing the margin from its own percentage would
    leak a yen or two per transaction — money created from nothing, and a
    reconciliation break that is miserable to trace.
    """
    merchant_discount = round_money(gross * merchant_discount_rate, currency)
    interchange = round_money(gross * interchange_rate, currency)
    scheme_fee = round_money(gross * scheme_fee_rate, currency)
    acquirer_margin = merchant_discount - interchange - scheme_fee

    return FeeSplit(
        gross=round_money(gross, currency),
        merchant_discount=merchant_discount,
        interchange=interchange,
        scheme_fee=scheme_fee,
        acquirer_margin=acquirer_margin,
        net_to_merchant=round_money(gross, currency) - merchant_discount,
    )
