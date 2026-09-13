# NexusPay — Status

Where the project stands, and what happens next. Update this at the end of each
session so any new session (or a reinstalled Claude) can pick up cold.

**Last updated:** 2026-09-13

---

## Where we are

**Phase 0.1 (Repository and tooling) — complete.** Everything in it was marked
*(C)*, so Claude scaffolded all of it. Both test suites were run and pass.

Nothing domain-related exists yet. No entities, no migrations, no pipelines —
that is deliberate, it's Sagar's work starting in Phase 1.

### Verified, not assumed

- `cd payment-core && ./mvnw verify` → BUILD SUCCESS. The Spring context starts
  against a **real PostgreSQL 16 container** via Testcontainers (no H2, per
  `CLAUDE.md`). Requires Docker Desktop to be running.
- `cd data-hub && .venv\Scripts\python.exe -m pytest -q` → 1 passed. Starts a
  real local `SparkSession` and counts rows. No manual env vars needed.
- **CI has never actually run** — there is no GitHub remote yet. The Phase 0.1
  "done when" says *pass in CI*, so that half is still unproven. This is the
  one loose end from Phase 0.

### What's in the repo

```
CLAUDE.md                     the working contract — read first, every session
docs/PHASES.md                roadmap + progress checkboxes (0.1 ticked)
docs/STATUS.md                this file
docs/LEARNING_LOG.md          empty, awaiting Sagar's first entry
payment-core/                 Spring Boot 3.5.16, Java 21, Maven wrapper
  src/main/java/com/nexuspay/ NexusPayApplication + empty domain packages
  src/test/                   context-load smoke test (Testcontainers Postgres)
data-hub/                     Python 3.12, PySpark 4.2, Delta 4.4, pytest
  generator/ pipelines/       real packages, empty
  tests/conftest.py           pins the Spark worker interpreter (see gotchas)
infra/docker-compose.yml      PostgreSQL only
.github/workflows/ci.yml      both suites; never executed yet
```

Two commits: `6a5aeb1` (scaffold), `c6cbdb7` (fixes). Local-only, branch `main`.

---

## Decisions made, and why

- **Spring Boot 3.5.16.** `CLAUDE.md` specifies 3.x. Spring Boot 4.x is out, but
  we stayed on the documented major version rather than silently upgrading.
- **Dependencies kept deliberately slim.** `pom.xml` has web, validation, JPA,
  PostgreSQL, Flyway, actuator and test only. Kafka, Redis, Spring Security and
  the observability stack are listed in `CLAUDE.md`'s stack but were *not* added
  yet — they belong to Phases 3 and 6. Add them when the phase needs them.
- **`docker-compose.yml` has PostgreSQL only**, for the same reason.
- **data-hub pinned to Python 3.12** (`requires-python = ">=3.12,<3.13"`) to
  match the Databricks runtime targeted in Phase 5, so the same transformation
  functions run locally and on Databricks. This is *not* a compatibility
  workaround — see gotchas.

---

## Gotchas worth remembering

- **Spark's Python worker interpreter.** Spark launches workers via
  `PYSPARK_PYTHON`, which defaults to the first `python` on PATH — not
  necessarily the interpreter running the tests. When they differ, the worker
  dies with a bare `Python worker exited unexpectedly (crashed)` and a
  `SocketException: Connection reset`, which names nothing useful.
  `data-hub/tests/conftest.py` pins it to `sys.executable`. If you ever run
  Spark outside pytest, set it yourself.
- **PySpark 4.2 works fine on Python 3.14.** Claude initially diagnosed a crash
  as a 3.14 incompatibility. That was wrong — the crash was the worker-interpreter
  problem above, and the real cause was only found by testing 3.14 again *with*
  `PYSPARK_PYTHON` set correctly. The 3.12 pin is for Databricks parity alone.
- **setuptools flat-layout.** With several top-level dirs, setuptools refuses to
  auto-discover packages and the install fails outright. `pyproject.toml`
  declares them explicitly under `[tool.setuptools.packages.find]`.
- Both of the above were invisible to compilation and would have passed a mocked
  test. They only surfaced by running the real thing — which is the argument for
  `CLAUDE.md`'s "real dependencies, no H2" rule, and a good `LEARNING_LOG` entry.

---

## Environment notes (Sagar's machine)

- Project lives on `E:\NexusPay`. Java 21.0.10 at
  `C:\Program Files\Java\jdk-21.0.10`, Maven 3.9.9, Docker Desktop 29.7.2,
  system Python 3.14.7. No `winget`, no `py` launcher, no `gh` CLI.
- **MSI installers fail on this machine** — the python.org 3.12 installer dies
  with exit code 3 / `0x80070003` (error 2203) even from an extracted layout
  with an explicit target dir. Defender and Controlled Folder Access ruled out;
  looks like a broken Windows Installer state. Don't retry MSI installs.
- **Use `uv` for Python versions instead.** Installed at
  `C:\Users\sagar\.local\bin\uv.exe`. Python 3.12.14 is at
  `C:\Users\sagar\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none\python.exe`.
  (`uv python install` prints a harmless symlink error on Windows; the
  interpreter still works at its full versioned path.)
- Docker Desktop is often not running at session start:
  `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"`, then give
  it a minute before anything Testcontainers-based.

---

## What's next

Phase 0.2 → 0.3 → 0.4, in order. These are **Sagar's writing, Claude teaching** —
the opposite balance from Phase 0.1.

### 0.2 Business domain *(S writes)*
Claude explains first, Sagar explains it back, then writes
`docs/business-requirements.md`: actors, flows, JPY, Asia/Tokyo, cut-off times.
Concepts: the four-party card model (cardholder, merchant, acquirer, issuer) and
the network in the middle; authorization vs capture; refund vs reversal;
clearing vs settlement; interchange and fees; why reconciliation exists.
**Done when** Sagar can narrate the life of one ¥5,000 payment from tap to
merchant bank account without notes.

### 0.3 Transaction lifecycle *(S writes)*
State diagram + transition table for
`RECEIVED → VALIDATED → RISK_CHECK → AUTHORIZED → CAPTURED → CLEARED → SETTLED`,
plus `DECLINED, REVERSED, EXPIRED, REFUNDED, FAILED`.
**Done when** every transition is either allowed with a reason, or explicitly
forbidden.

### 0.4 Data contract *(S writes, Claude reviews)*
`docs/data-contracts.md` — the agreement that lets both tracks proceed in
parallel. **Done when** the Phase 2 generator and the Phase 3 payment-core
exports could each be built from that document alone.

### Open loose end, any time
Create a GitHub remote and push, so CI actually runs and Phase 0.1's "done when"
is genuinely met. `gh` is not installed on this machine.

After Phase 0, the two tracks can run in parallel: Phase 1 (payment core) and
Phase 2 (data hub v1). Full roadmap in `docs/PHASES.md` — one phase at a time.

---

## How to resume

1. Start Claude in `E:\NexusPay`. It reads `CLAUDE.md` automatically.
2. Say: *"Read docs/STATUS.md and docs/PHASES.md, then let's start Phase 0.2."*
3. If Docker is needed, start Docker Desktop first.
4. To re-verify the scaffold still works:

```
cd payment-core && ./mvnw verify
cd data-hub && .venv\Scripts\python.exe -m pytest -q
```

If `data-hub/.venv` is missing (it is gitignored, so a fresh clone won't have
it), recreate it with the uv-managed 3.12 interpreter noted above:

```
& "C:\Users\sagar\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none\python.exe" -m venv .venv
.venv\Scripts\python.exe -m pip install -e ".[dev]"
```
