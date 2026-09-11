"""Smoke test: local Spark must start and run a trivial job."""
import pytest
from pyspark.sql import SparkSession


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[1]")
        .appName("data-hub-smoke-test")
        .getOrCreate()
    )
    yield session
    session.stop()


def test_spark_session_starts_and_counts_rows(spark):
    df = spark.createDataFrame([(1, "a"), (2, "b")], ["id", "value"])
    assert df.count() == 2
