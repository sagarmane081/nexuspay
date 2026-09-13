# NexusPay — Phases

Each step follows the same rhythm:

- **Learn** — concepts Claude explains first; Sagar explains them back.
- **Build** — what gets built. *(S)* = Sagar writes it, *(C)* = Claude may scaffold.
- **Tests** — what must be proven.
- **Done when** — the definition of done.
- **Talking point** — one sentence Sagar can use in an interview.

Tick boxes as you go. The two tracks (payment-core and data-hub) can run in parallel
after Phase 0, because the data contract is agreed up front and the data-hub starts
with a synthetic generator.

---

## Phase 0 — Foundations (original steps 1–2)

### 0.1 Repository and tooling
- [x] Monorepo created with the layout in `CLAUDE.md` *(C)*
- [x] `payment-core` Spring Boot skeleton with Maven wrapper *(C)*
- [x] `data-hub` Python project with PySpark, Delta Lake and pytest *(C)*
- [x] `infra/docker-compose.yml` with PostgreSQL *(C)*
- [x] GitHub Actions: build + test for both parts *(C)* — written; **not yet run, no remote**
- **Done when:** both `./mvnw verify` and `pytest` pass in CI on an empty project.
  - Both pass **locally** (2026-09-13): `./mvnw verify` green against a real
    Testcontainers Postgres; `pytest` green on a local SparkSession.
    Not yet proven *in CI* — no GitHub remote is configured yet.

### 0.2 Business domain
- **Learn:** four-party card model (cardholder, merchant, acquirer, issuer) and the
  network in the middle; authorization vs capture; refund vs reversal; clearing vs
  settlement; interchange and fees; why reconciliation exists.
- [x] `docs/business-requirements.md` *(C, per 2026-09-13 agreement)* — actors, flows, JPY, Asia/Tokyo, cut-off times
- **Done when:** Sagar can explain the life of one ¥5,000 payment from tap to
  merchant bank account without notes.
- **Talking point:** "I can walk through a card payment from authorization to settlement."

### 0.3 Transaction lifecycle
- **Learn:** state machines, allowed vs forbidden transitions, terminal states.
- [x] State diagram + transition table — see `docs/transaction-lifecycle.md`:
  `RECEIVED → VALIDATED → RISK_CHECK → AUTHORIZED → CAPTURED → CLEARED → SETTLED`,
  plus `DECLINED, REVERSED, EXPIRED, REFUNDED, FAILED`
- **Done when:** every transition is either allowed (with a reason) or explicitly forbidden.

### 0.4 Data contract (links both tracks)
- **Learn:** data contracts, schema versioning, why producers and consumers agree first.
- [x] `docs/data-contracts.md` *(C, per 2026-09-13 agreement)* — file formats and columns for
  payments, ledger entries, clearing file, settlement file; IDs, amounts, currency,
  timestamps, `source_file`, `batch_id`, `schema_version`
- **Done when:** the generator (Phase 2) and payment-core exports (Phase 3) can both
  be built from this document alone.

---

## Phase 1 — Payment core (original steps 3–8)

### 1.1 Domain + database design
- **Learn:** normalization for financial data, constraints as safety nets, why
  `DECIMAL` + currency code.
- [x] ER diagram — `docs/er-diagram.md`
- [x] Flyway migrations (V1-V5) — verified against PostgreSQL 16 for Customer, Account, Card, Merchant, Terminal, Payment,
  Authorization, Capture, Refund, Reversal, LedgerAccount, Journal, LedgerEntry,
  AuditEvent *(S; Claude may scaffold empty migration files)*
- **Done when:** DB constraints reject negative amounts, missing currency and invalid states.

### 1.2 Spring Boot foundation
- [x] Domain packages, bean-validation error contract, global exception handler, `/api/v1` — first concrete DTOs land with the first endpoint in 1.3
- [x] Controller → Application Service → Domain → Repository boundaries — enforced by `LayeringTest`; first vertical slice in 1.3
- **Done when:** a domain class has zero Spring web imports.

### 1.3 Customer, account, card, merchant (keep it slim)
- [x] Create/read APIs, card lifecycle `ACTIVE/BLOCKED/EXPIRED/CANCELLED`
- [x] Merchant + terminal management
- **Tests:** blocked/expired card cannot pay.
- **Done when:** enough exists to make a payment. Don't gold-plate this step.

### 1.4 Payment processing + state machine
- **Learn:** authorization holds, capture windows, partial capture (optional).
- [x] `POST /payments`, `GET /payments/{id}`, `POST /payments/{id}/capture|refund|reverse`
- [x] State machine enforced in the domain — all 144 transitions tested against the documented matrix
- **Tests:** every forbidden transition throws; refund cannot exceed captured amount.

### 1.5 Idempotency
- **Learn:** lost responses, client retries, request hashing, race conditions.
- [x] `Idempotency-Key` header; store key, request hash, status, response — required on every money-moving endpoint
- **Tests:** same key + same body → same response; same key + different body → 422;
  two concurrent identical requests → one payment.
- **Talking point:** "My APIs are safe to retry, and I proved it with concurrent tests."

### 1.6 Double-entry ledger
- **Learn:** journals vs entries, chart of accounts, reversal + correction entries.
- [ ] Immutable journals and entries; every posting balances *(S)*
- [ ] Postings for authorize, capture, refund, reverse *(S)*
- **Tests:** a parameterized test asserts debits = credits for **every** scenario;
  UPDATE/DELETE on ledger tables is impossible (DB permission or trigger).
- **Done when:** Phase 1 invariants hold under Testcontainers PostgreSQL in CI.

---

## Phase 2 — Data hub v1, local (can start right after Phase 0)

### 2.1 Synthetic data generator
- **Learn:** why realistic test data matters; seeded randomness for reproducible tests.
- [ ] Python generator that writes payments, ledger entries, clearing and settlement
  files exactly per `docs/data-contracts.md` *(S)*
- [ ] Options to inject problems: duplicates, negative amounts, unknown currency,
  late files, corrupt rows, missing settlement lines *(S)*
- **Done when:** the same seed produces the same files every time.

### 2.2 Lakehouse basics + Bronze
- **Learn:** data lake vs warehouse vs lakehouse; medallion architecture; Spark
  DataFrames and lazy evaluation; Delta Lake transaction log and ACID.
- [ ] Local Spark + Delta setup; landing folder (local or S3-compatible) *(C)*
- [ ] Bronze ingestion: raw, append-only, adds `source_file`, `ingested_at`, `batch_id` *(S)*
- **Tests:** row count in Bronze = rows in source files; re-ingesting the same file
  adds nothing.

### 2.3 Silver
- **Learn:** `MERGE` (upsert), deduplication, type casting, conformed data, quarantine tables.
- [ ] Deduplicate by business key, cast types, mask PAN (first 6 + last 4), route
  bad rows to a quarantine table with a reason *(S)*
- **Tests:** duplicates removed; every bad row is in quarantine with a reason;
  no raw PAN exists in Silver.

### 2.4 Gold
- **Learn:** facts vs dimensions, aggregates, business-ready marts.
- [ ] Daily settlement positions per participant, merchant revenue, reconciliation mart *(S)*
- **Tests (the core suite):**
  - count and JPY-sum reconciliation Source → Bronze → Silver → Gold
  - debits = credits preserved in every layer
  - every Gold row traces back to `source_file` + `batch_id`
- **Done when:** the whole suite runs in GitHub Actions on every PR.
- **Talking point:** "I built an integration test suite that proves a medallion
  pipeline doesn't lose or create money."

---

## Phase 3 — Risk + events (original steps 9–10)

### 3.1 Risk / fraud engine
- [ ] Configurable rules: amount threshold, velocity, blocked card, merchant restrictions *(S)*
- [ ] `APPROVE / REVIEW / DECLINE` with stored rule IDs, scores, timestamps *(S)*
- **Tests:** every decline has an explanation.

### 3.2 Kafka
- **Learn:** topics, partitions, consumer groups, offsets, at-least-once delivery,
  idempotent consumers, dead-letter topics, the transactional outbox pattern.
- [ ] Publish `PaymentReceived/Authorized/Declined/Captured/Reversed/Refunded` via outbox *(S)*
- [ ] Idempotent consumers with retries and DLT *(S)*
- **Tests:** a consumer processing the same event twice changes nothing; a poison
  message lands in the DLT.

### 3.3 Real exports to the data hub
- [ ] payment-core writes files per the data contract to the landing zone *(S)*
- [ ] Swap the generator for real exports in an end-to-end test *(S)*
- **Done when:** a payment made via the API shows up correctly in Gold.

---

## Phase 4 — Network back office (original steps 11–14)

### 4.1 ISO 8583 simulator
- **Learn:** MTI, bitmaps, data elements; 0100/0110, 0200/0210, 0400/0410; STAN, RRN, MCC.
- [ ] Encoder/decoder for a small field subset *(S)*
- [ ] Merchant → Acquirer → NexusPay → Issuer simulation *(S)*
- **Tests:** round-trip encode/decode; a 0400 reversal matches its original 0100.
- **Talking point:** "I implemented a subset of ISO 8583 authorization and reversal messages."

### 4.2 Clearing
- [ ] Batch captured transactions; gross, fees, adjustments, net *(S)*
- [ ] Batch lifecycle `CREATED → PROCESSING → COMPLETED / FAILED`; clearing file output *(S)*

### 4.3 Settlement
- **Learn:** gross vs net settlement, positions, approval workflows.
- [ ] Net positions per participant; approval + execution; retry on failure *(S)*
- **Tests:** settlement cannot run twice for one batch, even concurrently.

### 4.4 Reconciliation
- [ ] Compare internal records vs external settlement file *(S)*
- [ ] Detect missing, duplicate, amount, fee, currency, status mismatches;
  create exceptions; investigation/resolution APIs *(S)*
- [ ] Data hub: Gold reconciliation mart matches payment-core's exceptions *(S)*

---

## Phase 5 — Data hub v2 on Databricks + AWS Tokyo

- **Learn:** Databricks workspace basics, Auto Loader, Lakeflow Declarative Pipelines
  and expectations, Lakeflow Jobs, Unity Catalog (catalogs, schemas, grants, column
  masks, row filters, lineage), Delta time travel and `RESTORE`, schema evolution.
- [ ] Run the same transformation functions on Databricks Free Edition *(S)*
- [ ] Auto Loader ingestion into Bronze *(S)*
- [ ] Expectations for data quality (drop / quarantine / fail) *(S)*
- [ ] A Job orchestrating Bronze → Silver → Gold with retries *(S)*
- [ ] Unity Catalog: analyst role sees masked PAN; row filter by merchant *(S)*
- [ ] AWS: S3 landing bucket + least-privilege IAM in ap-northeast-1 via CloudFormation *(C scaffolds, S reviews)*
- **Tests (recovery + security):**
  - kill a job mid-run, rerun → identical Gold
  - bad load → `RESTORE` to previous version → reconciliation passes again
  - upstream adds a column → Bronze survives, contract test flags it before Silver
  - analyst role cannot read raw PAN
- **Note:** check Free Edition's current quotas before designing jobs; keep CI on the
  local track so tests never depend on Databricks being available.
- **Talking point:** "I tested orchestration recovery and Unity Catalog security,
  not just the happy path."

---

## Phase 6 — Security, audit, observability (original steps 16–18)

- [ ] OAuth2/JWT + roles `CUSTOMER, MERCHANT, OPERATIONS, SETTLEMENT_OPERATOR, ADMIN` *(S)*
- [ ] Masking, field encryption where needed, rate limiting, secrets out of code *(S)*
- [ ] Immutable audit trail for payment, refund, reversal, risk, settlement, admin actions *(S)*
- [ ] Data hub: audit tables and a documented retention policy (10-year design) *(S)*
- [ ] Actuator, Micrometer, Prometheus, Grafana, OpenTelemetry; correlation ID
  through API → Kafka → files → data hub *(C scaffolds infra, S instruments)*
- **Done when:** one payment can be traced across every system by a single ID.

---

## Phase 7 — Failure, performance, CI/CD (original steps 19–21)

- [ ] Failure tests: DB down, Kafka down, issuer timeout, consumer crash,
  partial settlement, corrupt clearing file *(S)*
- [ ] k6 load test: throughput, p95/p99, errors, DB connections, Kafka lag *(S)*
- [ ] CI pipeline: build → unit → integration → static/security scans → Docker build *(C)*
- **Done when:** results are written up in `docs/performance.md` with numbers.

---

## Phase 8 — Stretch (only if time allows)

- [ ] FX: JPY, USD, EUR, INR with stored rates (step 15)
- [ ] Kubernetes deployment, probes, autoscaling, pod-failure test (step 22)
- [ ] Operations dashboard (step 23)
- [ ] Incident write-ups in `docs/incidents/`: detection → impact → root cause →
  recovery → prevention (step 24)

---

## Final production review (step 25)

- [ ] No duplicate financial transactions
- [ ] No money created or lost; debits always equal credits
- [ ] Ledger history immutable
- [ ] Settlement cannot execute twice
- [ ] Every important action auditable
- [ ] Transactions traceable end to end, including the data hub
- [ ] Failures recover safely
- [ ] README with architecture diagram, how to run, and test evidence
