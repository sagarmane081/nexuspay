"""Deterministic identifier generation.

The contract asks for UUID v7 so identifiers sort by creation time. The standard
library cannot produce one, and ``uuid.uuid4()`` would defeat the point twice
over: no ordering, and no reproducibility.

Everything here draws from a caller-supplied ``random.Random``, so the same seed
always yields the same identifiers. That is what makes "the same seed produces
the same files every time" true rather than aspirational.
"""

from __future__ import annotations

import random


def uuid7(rng: random.Random, unix_millis: int) -> str:
    """Build a UUID v7 (RFC 9562) from a timestamp and a seeded RNG.

    Layout:
        bits 127..80  48-bit big-endian millisecond timestamp
        bits 79..76   version, 0b0111
        bits 75..64   12 random bits
        bits 63..62   variant, 0b10
        bits 61..0    62 random bits
    """
    if unix_millis < 0 or unix_millis >= 1 << 48:
        raise ValueError(f"timestamp does not fit in 48 bits: {unix_millis}")

    rand_a = rng.getrandbits(12)
    rand_b = rng.getrandbits(62)

    value = (unix_millis & 0xFFFF_FFFF_FFFF) << 80
    value |= 0x7 << 76
    value |= rand_a << 64
    value |= 0b10 << 62
    value |= rand_b

    hex_digits = f"{value:032x}"
    return "-".join((
        hex_digits[0:8],
        hex_digits[8:12],
        hex_digits[12:16],
        hex_digits[16:20],
        hex_digits[20:32],
    ))


def version_of(uuid_string: str) -> int:
    """The version nibble, for asserting the contract is met."""
    return int(uuid_string.replace("-", "")[12], 16)


def variant_of(uuid_string: str) -> int:
    """The two variant bits, which RFC 9562 requires to be 0b10."""
    return int(uuid_string.replace("-", "")[16], 16) >> 2


def auth_code(rng: random.Random) -> str:
    """Six alphanumeric characters, the shape issuers actually return."""
    alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return "".join(rng.choice(alphabet) for _ in range(6))
