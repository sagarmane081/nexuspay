"""Shared fixtures.

The SparkSession is module-scoped and reused: starting a JVM costs a few
seconds, and a session per test would dominate the suite's runtime.
"""

from __future__ import annotations

import os
import sys

import pytest

# Spark launches Python workers via PYSPARK_PYTHON, which defaults to the first
# `python` on PATH rather than the interpreter running pytest. When they differ
# the worker dies with a bare "Python worker exited unexpectedly" that names
# nothing useful. Pin it before pyspark is imported anywhere.
os.environ.setdefault("PYSPARK_PYTHON", sys.executable)
os.environ.setdefault("PYSPARK_DRIVER_PYTHON", sys.executable)


@pytest.fixture(scope="session")
def spark():
    from pipelines.spark import build_spark_session

    session = build_spark_session(app_name="nexuspay-tests")
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()
