"""Smoke test: local Spark and Delta must actually work.

The ``spark`` fixture comes from ``conftest.py`` and is deliberately *not*
redefined here.

This file used to declare its own module-scoped fixture that called
``getOrCreate()`` and then ``stop()`` on teardown. Spark is a JVM singleton, so
that fixture handed back the very session every other test was using and then
shut it down — and because pytest collects alphabetically, everything after
``test_environment`` failed with
``AttributeError: 'NoneType' object has no attribute 'setCallSite'``, PySpark's
way of saying the context is dead.

It stayed hidden for a phase: until Silver existed, no Spark test ran after this
one, and running a single file in isolation never triggers it.
"""

from __future__ import annotations


def test_spark_session_starts_and_counts_rows(spark):
    frame = spark.createDataFrame([(1, "a"), (2, "b")], ["id", "value"])

    assert frame.count() == 2


def test_delta_is_available(spark, tmp_path):
    """Delta must be on the classpath and able to write.

    Worth asserting explicitly: the JARs are downloaded on demand rather than
    vendored, and on Windows writing anything at all needs winutils. Both are
    environment setup that can silently regress.
    """
    path = str(tmp_path / "delta_probe")
    spark.createDataFrame([(1,)], ["n"]).write.format("delta").save(path)

    assert spark.read.format("delta").load(path).count() == 1


def test_session_is_shared_and_still_alive(spark):
    """Guards the bug this file used to cause.

    If any test stops the shared session, ``_jsc`` becomes None and every later
    Spark call dies with a confusing AttributeError rather than anything that
    names the real problem.
    """
    assert spark.sparkContext._jsc is not None, "the shared SparkContext has been stopped"
