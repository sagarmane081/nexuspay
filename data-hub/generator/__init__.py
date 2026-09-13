"""Synthetic NexusPay data, generated exactly per ``docs/data-contracts.md``.

Used until payment-core writes real exports in Phase 3.3. The point is not
realistic-looking data — it is data whose correctness properties are known in
advance, so the pipeline's reconciliation tests have something to be right
about, and data whose defects are deliberate, so the pipeline's failure
handling has something to survive.

Typical use::

    from datetime import date
    from pathlib import Path
    from generator import GeneratorConfig, generate

    generate(GeneratorConfig(business_date=date(2026, 9, 14), seed=42),
             Path("landing"))
"""

from __future__ import annotations

from pathlib import Path

from generator.config import Faults, GeneratorConfig
from generator.datasets import GeneratedBatch, build
from generator.writer import COLUMNS, file_name, write

__all__ = [
    "Faults",
    "GeneratorConfig",
    "GeneratedBatch",
    "COLUMNS",
    "build",
    "file_name",
    "generate",
    "write",
]


def generate(config: GeneratorConfig, output_dir: Path) -> dict[str, Path]:
    """Build and write one batch. Returns dataset name to the path written."""
    return write(build(config), config, output_dir)
