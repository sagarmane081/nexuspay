"""Local Spark + Delta setup.

Three environment problems are solved here, each of which cost real time to
diagnose and would otherwise bite every developer who clones this repo.

1. **Delta JARs without Ivy.** ``configure_spark_with_delta_pip`` resolves Delta
   through Ivy at session start. Ivy consults the local Maven cache first, and
   if Maven ever downloaded only a POM for a transitive dependency — which it
   does routinely for dependency management — Ivy reports the JAR missing and
   aborts rather than falling through to Maven Central. On this machine
   ``log4j-core:2.25.3`` was exactly that case, courtesy of payment-core's own
   builds. Pinning the JARs removes runtime resolution entirely: faster startup,
   works offline, and immune to whatever else shares ``~/.m2``.

2. **winutils on Windows.** Spark reads and computes fine on Windows without
   it, which is why a smoke test that only counts rows passes. Writing *any*
   format — CSV, Parquet, Delta — fails without ``winutils.exe`` and
   ``hadoop.dll``. Set up here for Windows only; Linux and CI need neither.

3. **The Python worker interpreter.** ``PYSPARK_PYTHON`` defaults to the first
   ``python`` on PATH, not the one running the tests. A mismatch kills the
   worker with a bare "Python worker exited unexpectedly".
"""

from __future__ import annotations

import os
import platform
import sys
import urllib.request
from pathlib import Path

from pyspark.sql import SparkSession

DATA_HUB_ROOT = Path(__file__).resolve().parent.parent

DELTA_VERSION = "4.4.0"
SCALA_BINARY_VERSION = "2.13"

#: Delta needs exactly these two. Everything else it uses is already inside
#: PySpark's bundled jars.
DELTA_JARS = {
    f"delta-spark_{SCALA_BINARY_VERSION}-{DELTA_VERSION}.jar":
        f"https://repo1.maven.org/maven2/io/delta/delta-spark_{SCALA_BINARY_VERSION}"
        f"/{DELTA_VERSION}/delta-spark_{SCALA_BINARY_VERSION}-{DELTA_VERSION}.jar",
    f"delta-storage-{DELTA_VERSION}.jar":
        f"https://repo1.maven.org/maven2/io/delta/delta-storage"
        f"/{DELTA_VERSION}/delta-storage-{DELTA_VERSION}.jar",
}

HADOOP_WINUTILS = {
    "winutils.exe": "https://raw.githubusercontent.com/cdarlint/winutils/master/hadoop-3.3.6/bin/winutils.exe",
    "hadoop.dll": "https://raw.githubusercontent.com/cdarlint/winutils/master/hadoop-3.3.6/bin/hadoop.dll",
}


def _download_missing(targets: dict[str, str], directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for name, url in targets.items():
        destination = directory / name
        if destination.exists() and destination.stat().st_size > 0:
            continue
        urllib.request.urlretrieve(url, destination)


def ensure_delta_jars(jars_dir: Path | None = None) -> list[Path]:
    """Return the Delta JAR paths, downloading them once if absent."""
    jars_dir = jars_dir or DATA_HUB_ROOT / "jars"
    _download_missing(DELTA_JARS, jars_dir)
    return [jars_dir / name for name in DELTA_JARS]


def ensure_hadoop_on_windows(hadoop_home: Path | None = None) -> Path | None:
    """Install ``winutils.exe`` and ``hadoop.dll``, on Windows only.

    Returns the HADOOP_HOME that was configured, or None on other platforms.
    """
    if platform.system() != "Windows":
        return None

    hadoop_home = hadoop_home or DATA_HUB_ROOT / "hadoop"
    _download_missing(HADOOP_WINUTILS, hadoop_home / "bin")

    os.environ["HADOOP_HOME"] = str(hadoop_home)
    # hadoop.dll is loaded from PATH rather than HADOOP_HOME.
    bin_dir = str(hadoop_home / "bin")
    if bin_dir not in os.environ.get("PATH", ""):
        os.environ["PATH"] = bin_dir + os.pathsep + os.environ.get("PATH", "")
    return hadoop_home


def build_spark_session(app_name: str = "nexuspay-data-hub", cores: str = "1") -> SparkSession:
    """A local Spark session with Delta enabled.

    ``local[1]`` by default: the datasets here are small, and a single core
    makes test output deterministic and readable. Production tuning is a Phase 5
    concern.
    """
    ensure_hadoop_on_windows()
    os.environ.setdefault("PYSPARK_PYTHON", sys.executable)
    os.environ.setdefault("PYSPARK_DRIVER_PYTHON", sys.executable)

    jars = [str(path) for path in ensure_delta_jars()]
    classpath = os.pathsep.join(jars)

    return (
        SparkSession.builder
        .master(f"local[{cores}]")
        .appName(app_name)
        # extraClassPath rather than spark.jars: `spark.jars` copies each JAR
        # into a temp staging directory per session, which Windows then fails
        # to delete on shutdown and reports as a noisy error long after the
        # work succeeded.
        .config("spark.driver.extraClassPath", classpath)
        .config("spark.executor.extraClassPath", classpath)
        .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
        .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
        # Small data: the default 200 shuffle partitions would create 200 tiny
        # files per write and dominate the runtime.
        .config("spark.sql.shuffle.partitions", "4")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.session.timeZone", "UTC")
        .getOrCreate()
    )
