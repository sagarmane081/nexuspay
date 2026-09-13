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
| 1.2 Spring Boot foundation | Done — 46 tests green |
| 1.3 Customer, account, card, merchant | Done — 79 tests green |
| 1.4 Payment processing + state machine | Done — 112 tests green |
| 1.5 Idempotency | Done — 120 tests green |
| 1.6 Double-entry ledger | Done — 140 tests green |
| **Phase 1 complete** | payment-core invariants hold under Testcontainers |
| 2.1 Synthetic data generator | Done — 40 data-hub tests green |
| 2.2 Lakehouse basics + Bronze | Done — 60 data-hub tests green |
| 2.3 Silver | Done — 79 data-hub tests green |

### Verified, versus merely written

**Verified 2026-09-13, after Docker was reinstalled:**
- `cd payment-core && ./mvnw verify` → BUILD SUCCESS. All five migrations apply
  to a real Testcontainers PostgreSQL 16.15, and **22 tests pass** — 1 context
  load plus 21 schema-constraint tests. Confirmed against the surefire XML, not
  just the console summary, because `@Nested` grouping made the console output
  misattribute the two top-level tests.
- `cd data-hub && .venv\Scripts\python.exe -m pytest -q` → 1 passed, real local
  `SparkSession`.
- Phase 1.2 brings the suite to **46 tests**. `LayeringTest` was verified by
  deliberately injecting a Spring import into `Money` and confirming it fails
  with the file and line — an architecture test that cannot fail is decoration.

**CI now runs on every push.** Remote is
`https://github.com/sagarmane081/nexuspay`. The first run found a real bug that
140 green local tests could not: `mvnw` was committed mode 100644, because Git
on Windows does not track the executable bit, so the Linux runner refused to
start the build. The data-hub job passed on Linux first time — Delta JARs
download on demand and the winutils path is correctly skipped.

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
  src/main/java/com/nexuspay/common/
    money/Money.java            currency-safe amounts; rejects JPY minor units
    time/BusinessCalendar.java  Asia/Tokyo 22:00 cut-off -> business date
    id/UuidV7.java              time-ordered IDs (RFC 9562)
    error/                      DomainException + stable ErrorCode, no HTTP types
    api/                        GlobalExceptionHandler (RFC 9457), CorrelationIdFilter
    config/                     NexusPayProperties — every [DECISION] value
  src/test/java/com/nexuspay/
    NexusPayApplicationTests    context loads, migrations apply
    SchemaConstraintsTest       21 tests proving constraints reject bad data
    architecture/LayeringTest   domain packages import no framework types
    common/                     Money, BusinessCalendar, UuidV7 unit tests
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
- **No PostgreSQL DOMAINs.** `money_amount` / `currency_code` were removed in
  Phase 1.3: a domain surfaces over JDBC as `Types#DISTINCT`, which Hibernate's
  `ddl-auto: validate` cannot reconcile with `BigDecimal` or `String`. Keeping
  schema validation is worth more than the type alias, and the real guarantees
  were always the CHECK constraints, which are preserved per column.
- **Columns are VARCHAR, not CHAR.** CHAR pads with spaces, so `'JP'` silently
  becomes `'JP '`; it also mismatches Hibernate's expectation for a String.
- **Card tokens are deterministic** (HMAC of the PAN). The UNIQUE index on
  `card_token` therefore rejects registering the same card twice — and tests
  must each use a distinct test PAN.
- **Refunds take a row-level write lock** (`PaymentRepository.findByIdForUpdate`).
  Verified by removing it: two concurrent ¥3,000 refunds against a ¥5,000
  capture then BOTH succeeded — ¥6,000 returned, money created from nothing.
  The DB CHECK does not catch it, because each individual write looks valid.
- **Refund requires SETTLED; reversal requires pre-settlement.** Before cash
  moves there is nothing to send back, so the hold is released instead.
- **Clearing and settlement transitions are service methods, not endpoints.**
  `markCleared` / `markSettled` are batch outcomes that Phase 4 will drive; they
  exist now only so refunds are reachable and testable.
- **Idempotency rests on a UNIQUE index, not a prior existence check.** "Look,
  then insert" is the broken version: two concurrent requests both look, both
  see nothing, both proceed. Letting the database reject the second insert makes
  the race impossible rather than merely unlikely.
- **`IdempotencyStore` is a separate bean on purpose.** `@Transactional` works
  through a proxy, and a self-invocation never reaches it — `REQUIRES_NEW` would
  have been silently ignored, the claim would not have committed, and both
  concurrent requests would have created a payment. An annotation that looks
  correct while doing nothing.
- **`Idempotency-Key` is mandatory, not optional**, on every money-moving
  endpoint. An optional safety mechanism is the one omitted by the client least
  able to handle a duplicate charge.
- **A failed operation releases its key.** The operation is transactional, so
  nothing committed; burning the key would leave the client unable to ever
  complete that request.
- **An authorization posts no journal.** `PHASES.md` asked for postings on
  authorize; `business-requirements.md` §4 says a hold moves no value. The
  documented decision wins — the ledger begins at capture.
- **The ledger balance is enforced twice, on purpose.** The domain check gives a
  readable error naming both totals; the deferred trigger is the guarantee.
  Verified by disabling the domain check and posting a capture off by one yen:
  PostgreSQL refused the COMMIT, rolling back the capture along with it.
- **Capture posts gross; clearing will adjust to net.** Fees are a clearing-time
  calculation, so at capture the interchange is not yet known. Phase 4.2 posts a
  second journal taking the positions down to the figures in
  `business-requirements.md` §7.
- **The settlement file needed a `NETWORK` participant.** `data-contracts.md`
  asserted Σ`net_amount` = 0, but with only ACQUIRER and ISSUER that is
  arithmetically impossible — the issuer pays ¥4,900, the acquirer receives
  ¥4,885, and the ¥15 scheme fee belongs to NexusPay. Corrected during Phase 2.1.
- **The generator never reads the wall clock.** Every timestamp derives from
  `business_date`, and every identifier from the seeded RNG. Verified by
  injecting `time.time()` and confirming both determinism tests fail.
- **Spark on Windows needs winutils to write anything.** Reads and computes
  work without it, which is why a smoke test that only counts rows passes.
  `pipelines/spark.py` installs `winutils.exe` + `hadoop.dll` (Hadoop 3.3.6
  community build) into `data-hub/hadoop/`, on Windows only. CI on Linux needs
  neither.
- **Delta JARs are pinned, not resolved through Ivy.** `configure_spark_with_delta_pip`
  consults the local Maven cache first; payment-core's builds had left a
  POM-only entry for `log4j-core:2.25.3`, and Ivy aborts rather than falling
  through to Maven Central. The JARs are downloaded once into `data-hub/jars/`.
- **Bronze reads everything as a string and rejects nothing.** Casting there
  would turn a bad row into a null before Silver could quarantine it with a
  reason. The one exception is a file whose name disagrees with its rows, which
  is refused outright.
- **Silver validates before casting.** Once `"-5000"` is cast it is just a
  number and `"ZZZ"` is just a null; the evidence of *why* a row is wrong is
  gone, and a quarantine row reading "it was null" helps nobody.
- **Quarantine redacts card data.** A row is quarantined as `UNMASKED_PAN`
  precisely because a full card number arrived — writing it verbatim would
  relocate the leak to a table with less scrutiny, not contain it.
- **An unbalanced journal fails the pipeline; it is never quarantined.** Every
  other defect affects some rows. This one means money was created or
  destroyed, and quarantining it would let the run report success.
- **Validation rules are callables, not Columns.** A PySpark `Column` is a
  handle into a live JVM: building the rule table eagerly made the module
  unimportable before a session existed, breaking linters and editors.
- **Generator faults claim disjoint rows.** They all used to start at row 0, so
  three faults landed on one payment and Silver — which reports the first
  matching rule per row — made two of them invisible.
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

1. **Phase 2** — data hub v1: synthetic generator, Bronze/Silver/Gold, the
   reconciliation suite. Can run in parallel with Phase 3.
2. **Any time:** create a GitHub remote so CI actually runs.

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
