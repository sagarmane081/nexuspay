import os
import sys

# Spark launches its Python workers via PYSPARK_PYTHON, which defaults to the
# first `python` on PATH — not necessarily the interpreter running the tests.
# When they differ the worker dies with a bare "Python worker exited
# unexpectedly (crashed)". Pin both to this interpreter.
os.environ.setdefault("PYSPARK_PYTHON", sys.executable)
os.environ.setdefault("PYSPARK_DRIVER_PYTHON", sys.executable)
