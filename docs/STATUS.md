# NexusPay — Status

Where the project stands, and what happens next. Update this at the end of each
session so any new session can pick up cold.

**Last updated:** 2026-09-13

---

## Working agreement — changed 2026-09-13

This began as a coaching project in which Sagar wrote all domain-critical code.
He has since asked Claude to implement directly, having weighed the trade-off.
`CLAUDE.md` was amended to match. Claude still explains every money-critical
decision, because the project's value is still that Sagar can defend it.

**Target:** Phases 1 and 2 complete and correct, within one to two months.

---

## Where we are

| Phase | State |
|---|---|
| 0.1 Repository and tooling | Done, and verified running |
| 0.2 Business domain | Done — `docs/business-requirements.md` |
| 0.3 Transaction lifecycle | Done — `docs/transaction-lifecycle.md` |
| 0.4 Data contract | Done — `docs/data-contracts.md` |
| 1.1 Domain + database design | Done, verified against PostgreSQL 16.15 |

### Verified, versus merely written

**Verified 2026-09-13, after Docker was reinstalled:**
- `cd payment-core && ./mvnw verify` → BUILD SUCCESS. All five migrations apply
  to a real Testcontainers PostgreSQL 16.15, and **22 tests pass** — 1 context
  load plus 21 schema-constraint tests. Confirmed against the surefire XML, not
  just the console summary, because `@Nested` grouping made the console output
  misattribute the two top-level tests.
- `cd data-hub && .venv\Scripts\python.exe -m pytest -q` → 1 passed, real local
  `SparkSession`.

**Still NOT verified:**
- **CI has never run.** There is still no GitHub remote. Everything green above
  is green on one machine only.

### Docker was reinstalled — note the new path

Docker Desktop was uninstalled mid-session on 2026-09-13 and reinstalled the
same day. **It now installs per-user**, at
`C:\Users\sagar\AppData\Local\Programs\DockerDesktop\`, not
`C:\Program Files\Docker\Docker\`. Scripts hardcoding the old path will fail.

Windows 11 **Home** offers no Hyper-V, so Docker Desktop must use the WSL2
backend. WSL 2.3.26 is installed with `VirtualMachinePlatform` enabled and **no
Linux distribution**, which is correct — Docker manages its own internal distro
and never needs a user distro.

A shell started before the install will not have `docker` on `PATH`; refresh it
with `[System.Environment]::GetEnvironmentVariable("Path","Machine")` or start a
new shell. Testcontainers itself connects over the named pipe
`\\.\pipe\docker_engine` and does not need the CLI on `PATH`.

---

## What's in the repo

```
CLAUDE.md                       working contract — read first
docs/
  PHASES.md                     roadmap; 0.1–0.4 ticked
  STATUS.md                     this file
  business-requirements.md      actors, ¥5,000 flow, fees, cut-offs  [DECISION] tags
  transaction-lifecycle.md      12 states, full transition matrix
  data-contracts.md             CSV schemas for the 4 datasets
  er-diagram.md                 Mermaid ER diagram + design notes
  LEARNING_LOG.md               still empty
payment-core/
  src/main/resources/db/migration/
    V1__foundation.sql          domains, minor-unit check, append-only function
    V2__parties.sql             customer, account, card, merchant, terminal
    V3__payment.sql             payment, payment_authorization, capture, refund, reversal
    V4__ledger.sql              ledger_account, journal, ledger_entry + both triggers
    V5__audit.sql               audit_event + append-only trigger
  src/test/java/com/nexuspay/
    NexusPayApplicationTests    context loads, migrations apply
    SchemaConstraintsTest       21 tests proving constraints reject bad data
    support/PostgresTestcontainer  shared container, one context for the suite
data-hub/                       Python 3.12, PySpark 4.2, Delta 4.4 — skeleton only
infra/docker-compose.yml        PostgreSQL only
.github/workflows/ci.yml        both suites; never executed
```

---

## Decisions worth remembering

- **Spring Boot 3.5.16**, not 4.x — `CLAUDE.md` specifies 3.x.
- **Dependencies stay slim.** Kafka, Redis, Security and observability are added
  in the phases that need them (3 and 6), not speculatively.
- **`authorization` is a PostgreSQL reserved word** — the table is
  `payment_authorization`. Caught before it ever ran.
- **Holds are not ledger entries.** `available = balance − Σ(active holds)`,
  derived at read time. Reasoning in `business-requirements.md` §4.
- **Acquirer margin is a residual**, not a fourth percentage. Rounding three
  independent percentages against JPY's zero minor units leaks yen, which is
  money created from nothing. Reasoning in `business-requirements.md` §7.
- **Ledger amounts are always positive**; `direction` carries the sign. Two ways
  to express one movement would make sums depend on which was used.
- **Journal balance is a DEFERRABLE INITIALLY DEFERRED constraint trigger**, so
  it checks at COMMIT. Per-statement checking would reject every valid journal,
  since entries are inserted one row at a time.
- **CSV, not Parquet,** for the data contract — the failure modes the Phase 2
  suite must catch (corrupt rows, truncation, schema drift) are only
  reproducible in a text format.

---

## Gotchas

- **Spark's worker interpreter.** `PYSPARK_PYTHON` defaults to the first
  `python` on PATH, not the one running pytest. Mismatch gives a useless
  `Python worker exited unexpectedly`. `data-hub/tests/conftest.py` pins it.
- **PySpark 4.2 works fine on Python 3.14.** An earlier diagnosis blaming 3.14
  was wrong; the real cause was the worker interpreter above. The 3.12 pin is
  for Databricks parity only.
- **MSIX path redirection.** Inside Claude Desktop, `%LOCALAPPDATA%` resolves
  into `...\Packages\Claude_pzs8sxrjxfjjc\LocalCache\Local\`. The same files
  appear under two paths — do not double-count them when measuring disk usage.

---

## Environment (Sagar's machine)

- Project on `E:\NexusPay`. Java 21.0.10, Maven 3.9.9, system Python 3.14.7.
  Docker Desktop 29.7.2 (per-user install, see above). **No `winget`, no `py`
  launcher, no `gh` CLI.**
- **MSI installers fail** — python.org's 3.12 installer dies with error 2203 /
  `0x80070003`. Use `uv` instead: `C:\Users\sagar\.local\bin\uv.exe`. Python
  3.12.14 lives at
  `C:\Users\sagar\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none\python.exe`.
- `C:` space is watched closely; prefer `E:` for bulky artifacts.

---

## What's next

1. **Phase 1.2** — Spring layering: domain packages, DTOs, bean validation,
   global exception handler, `/api/v1`, with domain classes free of Spring web imports.
2. **Phase 1.3** — customer/account/card/merchant CRUD, card lifecycle.
3. **Phase 1.4** — payment API and the state machine from `transaction-lifecycle.md`.
4. **Phase 1.5** — idempotency. **Phase 1.6** — the double-entry ledger.
5. **Any time:** create a GitHub remote so CI actually runs.

---

## How to resume

1. Start Claude in `E:\NexusPay`; it reads `CLAUDE.md` automatically.
2. Say: *"Read docs/STATUS.md, then continue."*
3. Start Docker Desktop before anything Testcontainers-based.

```
cd payment-core && ./mvnw verify
cd data-hub && .venv\Scripts\python.exe -m pytest -q
```

If `data-hub/.venv` is missing (it is gitignored):

```
& "C:\Users\sagar\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none\python.exe" -m venv .venv
.venv\Scripts\python.exe -m pip install -e ".[dev]"
```
