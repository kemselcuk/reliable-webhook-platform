# Reliable Webhook Platform — Development Plan

## Project goal

Build a production-like, fully local and free-to-run webhook/event delivery platform that demonstrates durable asynchronous processing, transactional outbox, at-least-once delivery, idempotency, concurrency safety, retries, failure recovery, HMAC signing, observability, and failure-focused testing. A deliberately small React/TypeScript UI will exercise the real Spring Boot REST API and operational workflows.

## Architecture summary

The system is a monorepo with a Java 21/Spring Boot backend, React/TypeScript frontend, PostgreSQL as the durable source of truth, and Apache Kafka as the asynchronous transport. Maven is used for the backend because its explicit lifecycle and broad Spring/Testcontainers support make the build easy to understand in CI. Docker Compose provides all local infrastructure without paid services.

Event creation persists `Event`, `Delivery`, and `OutboxEvent` records in one PostgreSQL transaction. A polling publisher moves compact delivery references to Kafka. Workers atomically claim eligible deliveries, load current state from PostgreSQL, sign and send bounded-concurrency HTTP requests, then persist attempts and state transitions. Retries are durable database state and are requeued through the outbox. Delivery semantics are at-least-once; receivers use stable identifiers and idempotent handling.

The polling publisher may publish a Kafka record and crash before marking its outbox row published. That unavoidable window can produce duplicates and is intentionally handled by worker-side state checks and atomic claims; no exactly-once claim is made.

## Status legend

- `[ ]` Planned
- `[~]` In progress
- `[x]` Completed
- `[!]` Blocked

## Current status

- Current phase: Phase 9 — Hardening `[~]` in progress on `feature/phase-9-hardening`
- Overall status: Phases 0–8 `[x]` completed and merged to `main`; Phase 9 `[~]` in progress
- Completed: durable outbox/retry/replay; API idempotency and concurrency safety; HMAC security; bounded observability; browser operations; critical-path failure/retry/success coverage; PostgreSQL query/locking review; bounded local resources; dependency-aware health; focused diagrams; and the reproducible optional demo receiver
- Next: push the feature, verify feature CI, merge to `main`, and verify main CI
- Environment note: local port `5432` was already occupied during final verification, so the full stack was successfully verified with the documented host-port overrides (`55432/59092/18080/13000`). This does not change container ports or application topology.
- Intentionally deferred: CDC/Debezium, multi-tenancy, full secret rotation, OpenTelemetry, hosted deployment, and business-state use of a Kafka DLQ

## Phase 0 — Repository foundation `[x]`

Features and tasks:

- [x] Inspect local workspace and GitHub remote without overwriting existing work
- [x] Initialize `main`, connect `origin`, and create this living plan
- [x] Create `feature/phase-0-foundation`
- [x] Add the root agent working agreement and session-recovery workflow
- [x] Scaffold Java 21 Spring Boot backend with Maven
- [x] Scaffold minimal React + TypeScript frontend
- [x] Add Dockerfiles and Docker Compose foundation
- [x] Add PostgreSQL and single-node Apache Kafka in local Compose
- [x] Add backend/frontend health-oriented starter behavior
- [x] Add README, architecture documentation, and Git/GitHub workflow
- [x] Add GitHub Actions jobs for backend build/tests and frontend build/tests
- [x] Build backend and frontend locally
- [x] Start PostgreSQL/Kafka locally and verify health/readiness
- [x] Build and verify the complete Compose path, including backend readiness, frontend health, and nginx API proxying
- [x] Review the implementation, tests, documentation, and Phase 0 acceptance evidence

Acceptance criteria:

- [x] Backend build succeeds
- [x] Frontend build succeeds
- [x] PostgreSQL and Kafka start locally with Docker Compose and pass health checks
- [x] CI definition covers build, unit tests, and an integration-test path
- [x] Foundational docs explain setup, architecture direction, workflow, and current status

## Phase 1 — Core domain + PostgreSQL `[x]`

Features and tasks:

- [x] Add Spring Data JPA, PostgreSQL, Flyway, and PostgreSQL Testcontainers foundations
- [x] Implement `WebhookEndpoint`, `Event`, `Delivery`, and `DeliveryAttempt` persistence models and repositories
- [x] Add Flyway migrations, foreign keys, constraints, unique constraints, and query-driven indexes
- [x] Add endpoint create/list and event submission REST APIs
- [x] Add minimal endpoint create/list and event submit UI
- [x] Add PostgreSQL integration tests

Acceptance criteria:

- [x] Endpoints and events can be created through documented APIs
- [x] Schema is created exclusively through versioned migrations
- [x] Core persistence/API integration tests pass against PostgreSQL

## Phase 2 — Transactional outbox `[x]`

Features and tasks:

- [x] Persist `Event`, `Delivery`, and `OutboxEvent` in one transaction
- [x] Implement a batched polling publisher with safe concurrent claiming
- [x] Define compact, versioned Kafka delivery command contract
- [x] Preserve pending outbox state across Kafka outages

Acceptance criteria:

- [x] Business state and publish intent are atomic
- [x] Kafka unavailability never loses the outbox record
- [x] Publishing resumes after Kafka recovery; duplicate-publication behavior is tested and documented

## Phase 3 — Kafka + delivery worker `[x]`

Features and tasks:

- [x] Configure topic, producer, consumer group, partitions, keys, acknowledgements, and error handling
- [x] Implement atomic delivery claim/check and current-state load
- [x] Implement lease/token-based delivery claiming with a claim timeout and stale-claim recovery
- [x] Keep external HTTP outside database transactions/row locks and validate the lease token on completion
- [x] Implement pooled HTTP delivery with connect/response timeouts and controlled concurrency
- [x] Persist `DeliveryAttempt` and success/failure state
- [x] Add WireMock/Testcontainers end-to-end tests

Acceptance criteria:

- [x] Event submission reaches a WireMock webhook asynchronously through PostgreSQL, outbox, Kafka, and worker
- [x] A 2xx response results in `SUCCESS` with a recorded attempt
- [x] A crashed worker's expired lease is recovered, and a stale worker cannot overwrite a newer claim

## Phase 4 — Reliable retry `[x]`

Features and tasks:

- [x] Classify 2xx, 429/`Retry-After`, retryable 5xx/network failures, and permanent 4xx
- [x] Persist lifetime/run attempt counts, next retry time, and coherent status transitions
- [x] Implement exponential backoff, jitter, max attempts, and injectable time/randomness
- [x] Requeue due retries through the transactional outbox
- [x] Implement `DEAD` transition and manual replay

Acceptance criteria:

- [x] `500 → 500 → 200` reaches `SUCCESS` automatically
- [x] Timeouts retry; permanent failures do not retry unnecessarily
- [x] Max attempts result in `DEAD`; manual replay works

## Phase 5 — Idempotency + concurrency safety `[x]`

Features and tasks:

- [x] Implement API `Idempotency-Key`, canonical request hash, stored response reference, and unique constraint
- [x] Reject reuse of a key with a different payload
- [x] Harden atomic delivery claiming and duplicate Kafka message handling
- [x] Add concurrent request and worker tests

Acceptance criteria:

- [x] Concurrent identical keys create one logical event and return a consistent result
- [x] Concurrent workers cannot actively process the same delivery
- [x] Duplicate Kafka messages do not corrupt or repeat completed work

## Phase 6 — HMAC security `[x]`

Features and tasks:

- [x] Store a per-endpoint secret using a design that permits later rotation
- [x] Sign raw body and timestamp with versioned HMAC-SHA256
- [x] Send stable webhook/delivery IDs, timestamp, and signature headers
- [x] Document verification and replay-tolerance window
- [x] Prevent secret and sensitive-payload logging

Acceptance criteria:

- [x] Valid signatures verify; body modification invalidates them
- [x] Timestamp is part of the signed content and verification samples are documented/tested

## Phase 7 — Observability `[x]`

Features and tasks:

- [x] Add Actuator, Micrometer, Prometheus, and Grafana
- [x] Add structured logs with event/delivery correlation and redaction
- [x] Add bounded-cardinality throughput, outcome, retry, dead, latency, backlog, and consumer-lag metrics
- [x] Add a useful local Grafana dashboard
- [x] Reassess OpenTelemetry only if cross-boundary trace context adds demonstrable value

Acceptance criteria:

- [x] Grafana shows system health and delivery behavior from local Compose
- [x] Retry/failure/load scenarios visibly affect the expected metrics without high-cardinality labels

## Phase 8 — Frontend completion `[x]`

Features and tasks:

- [x] Complete endpoint management and event submission flows
- [x] Add delivery list/detail and attempt history
- [x] Add replay action for eligible deliveries
- [x] Add a minimal system health/metrics view and clear API error states

Acceptance criteria:

- [x] The complete platform demo workflow can be operated from a browser
- [x] UI remains intentionally small and uses no paid or unnecessary heavy dependency

## Phase 9 — Hardening `[~]`

Features and tasks:

- [x] Complete critical integration/end-to-end and failure-path coverage
- [x] Review query plans, indexes, locking, resource limits, and container health checks
- [x] Finalize CI, Docker experience, security guidance, and docs
- [x] Add focused architecture diagrams and README demo/use case
- [x] Run the final failure-then-retry demo and verify metrics

Acceptance criteria:

- [x] Full build and critical test suite pass from a clean checkout
- [x] Final demo shows endpoint creation, event submission, failed attempt, retry, success, attempt history, and metric changes
- [ ] `main` is stable, documented, and reproducible with free local tooling

## Cross-cutting engineering rules

- PostgreSQL is authoritative business state; Kafka provides decoupling, buffering, and horizontal consumption.
- External HTTP cannot participate in the database transaction, so delivery is explicitly at-least-once.
- No retry uses in-memory-only scheduling or `Thread.sleep`; time is injectable in tests.
- Metrics never label event IDs, delivery IDs, full URLs, or other unbounded values.
- No mandatory paid API, SaaS, cloud account, or subscription may be introduced.
- Significant work uses a feature/fix/refactor/docs branch. The manager reviews implementation, tests, docs, and acceptance criteria before merging.
- Each meaningful working increment is committed and pushed. The implementer never merges to `main`; branch lifecycle and merges remain manager-owned.
- This file is updated whenever a phase/feature status or material technical direction changes.

## Plan deviations / decision changes

`Planned: one attempt_count represented the delivery retry budget and attempt history.`

`Implemented: deliveries keep a monotonic lifetime attempt_count for immutable attempt numbering and a resettable run_attempt_count for each replay run.`

`Reason: manual replay needs a fresh maximum-attempt budget without renumbering or deleting the original delivery history.`

`Trade-off: the schema has one additional counter and coherence invariant, which makes state transitions slightly more explicit while preserving auditability.`

`Planned: the unique idempotency-key constraint would prevent duplicate stored records while API requests checked for an existing key before creating event state.`

`Implemented: event creation takes a PostgreSQL transaction-scoped advisory lock derived from the normalized Idempotency-Key before checking or creating its stored result; the unique constraint remains the final integrity guard.`

`Reason: a unique constraint alone rolls back the losing transaction but cannot make concurrent identical requests return the same successful response. Per-key serialization works across application instances and keeps event, delivery, outbox, and response persistence atomic.`

`Trade-off: requests sharing a key wait for the current key owner, and the 64-bit PostgreSQL hash can very rarely serialize unrelated colliding keys; collisions affect throughput only, not correctness.`

`Planned: each endpoint would store a signing secret in a design that permits later rotation.`

`Implemented: a separate webhook_endpoint_secrets table stores recoverable 32–512 byte secret material with per-endpoint version/key identifiers, history-capable rows, and a partial unique index allowing exactly one active key. New endpoints accept caller-provided material; V5 upgrades receive cryptographically random 32-byte backfill values.`

`Reason: outbound HMAC calculation requires the original secret bytes, while a separate table keeps material out of endpoint DTOs and compact Kafka commands and provides the schema boundary for future overlapping-key rotation.`

`Trade-off: PostgreSQL and its backups contain recoverable secret material and must be protected. Existing V5 receivers require a controlled provisioning step after backfill; complete rotation workflows and application-level encryption/key management remain deferred.`

`Planned: Phase 9 would harden container health checks and degraded-mode behavior.`

`Implemented: backend readiness includes PostgreSQL but intentionally excludes Kafka after startup; liveness remains process-local. Compose startup still waits for both PostgreSQL and Kafka before launching the backend.`

`Reason: PostgreSQL is required for the atomic durable write, while a Kafka outage is a supported degraded mode in which outbox intent remains authoritative and can publish after broker recovery.`

`Trade-off: an instance may report ready while asynchronous publication is delayed, so operators must use the outbox/Kafka metrics and dashboard alongside readiness.`

`Planned: the final hardening demo would be reproducible with free local tooling.`

`Implemented: an optional Compose profile runs a non-root, read-only, dependency-free receiver that returns one 500 then 204; a standard-library verification script exercises the real API/Kafka/worker/detail/metrics path and fails on any unexpected attempt or metric delta.`

`Reason: the acceptance flow should run from a clean checkout without a hosted receiver, paid service, or extra host language dependency.`

`Trade-off: enabling the profile pulls one additional Python Alpine image and the receiver is deliberately test-only rather than a production webhook implementation.`

Record future material changes as: `Planned`, `Implemented`, `Reason`, and `Trade-off`.

## Environment notes

- Requested implementer configuration is available and will be used: Luna with `xhigh` reasoning.
- The active root session cannot change its own model at runtime. It remains the manager/reviewer at the current environment-provided model and reasoning level; this is the closest available behavior to the requested Sol/high manager without silently substituting a spawned third role.
- Local tools detected at initialization: Java 21 is installed (Maven itself currently launches on Java 25), Maven 3.9.11, Node.js 22.23.2, npm 10.9.8, Docker 28.3.2, and Docker Compose 2.38.2.
- Phase 0 final verification used host-port overrides because local port `5432` was occupied. PostgreSQL, Kafka, backend, and frontend all reported healthy; direct backend health/readiness, frontend HTTP, and frontend-to-backend proxy requests returned HTTP 200. The frontend healthcheck was corrected to use `127.0.0.1` because the image resolved `localhost` to IPv6 while nginx listened on IPv4.
- Phase 1 final verification used a separate Compose project and fresh named volumes to prove clean Flyway startup without deleting existing local data. The default project volume in this workspace contains an earlier, unpublished V1 migration draft from implementation and therefore correctly fails Flyway checksum validation until that development-only volume is intentionally recreated.
- Phase 2 final verification used the separate `rwp-phase2-verify` Compose project with fresh volumes and host ports `56432/59095/18083/13003`. Normal publication and a live Kafka stop/start were exercised: the outage row remained durable as `PENDING/TIMEOUT`, recovered to `PUBLISHED`, and duplicate Kafka commands were observed as permitted by the documented at-least-once boundary.
- Phase 3 final verification used the separate `rwp-phase3-verify` Compose project with fresh volumes and host ports `57432/59096/18084/13004`, plus a temporary local receiver on `18091`. A real API event reached the receiver once with stable headers; PostgreSQL showed `SUCCESS`, one HTTP `204` attempt, and a `PUBLISHED` outbox row. Backend verification passed 18 unit and 29 integration tests; frontend lint, typecheck, 6 tests, production build, and Compose validation passed.
- Phase 4 acceptance verification passed backend `./mvnw verify` with 41 unit tests and 46 integration tests, including the real PostgreSQL + Kafka + WireMock retry pipeline; frontend lint, typecheck, 6 tests, and production build passed; `docker compose config` passed. Feature and `main` CI are green, and the phase is merged to `main` at `3069c34`.
- Phase 5 acceptance verification passed backend `./mvnw verify` with 43 unit tests and 51 integration tests, including concurrent API requests, concurrent PostgreSQL-backed workers, and the duplicate Kafka command pipeline; frontend lint, typecheck, 6 tests, and production build passed; `docker compose config --quiet` and `git diff --check` passed. Feature CI run `35368711343` and merged `main` CI run `35368978419` are green; the phase is merged to `main` at `84aa612`.
- Phase 6 acceptance verification passed backend `./mvnw verify` with 48 unit tests and 54 integration tests, including V5-to-V6 secret backfill, PostgreSQL constraints, API non-exposure, concurrent claim secret loading, exact WireMock request signing, and the Kafka delivery/retry pipelines; frontend lint, typecheck, 7 tests, and production build passed; `docker compose config --quiet` and `git diff --check` passed. Feature CI run `35407088320` and merged `main` CI run `35407250000` are green; the phase is merged to `main` at `ea659b9`.
- Phase 7 acceptance verification passed backend `./mvnw verify` with 51 unit tests and 55 integration tests; frontend lint, typecheck, 7 tests, and production build; Compose, Prometheus, and dashboard syntax checks; and a fresh six-service isolated Compose run. Five forced connection-failure events produced 20 durable retry transitions, 5 `DEAD` transitions, a zero final retry backlog, HTTP latency samples, correlated ECS logs without payload/secret material, a healthy Prometheus target, and a provisioned Grafana dashboard. Host ports `58432/59097/18087/13007/19090/23001` avoided occupied local ports; verification containers/network were removed and the isolated named volumes were preserved. Feature CI run `35447519218` and merged `main` CI run `35447638902` are green; the phase is merged to `main` at `8556cf8`.
- Phase 8 acceptance verification passed backend `./mvnw verify` with 51 unit and 59 integration tests; frontend lint, typecheck, 9 tests, and production build; `docker compose config --quiet`; and `git diff --check`. A fresh six-service Compose project on host ports `59432/59098/18088/13008/19091/23002` was operated through Chrome and the real API: endpoint enable/disable, idempotent event submission, durable failure retries to `DEAD`, paginated delivery metadata, ordered attempt history, eligible replay with current-run reset, and the bounded system summary were all observed. Delivery reads omitted the submitted payload and secret. Verification containers/network were removed without deleting the isolated named volumes. Feature CI run `35453318281` and merged `main` CI run `35466213190` are green; the phase is merged to `main` at `566623d`.
- Phase 9 pre-merge verification passed from a clean archive of `ea08207`: backend `./mvnw verify` ran 53 unit and 60 integration tests; fresh `npm ci`, lint, typecheck, 9 tests, and production build passed; both Compose models and Python demo scripts validated. A fresh seven-service `phase9clean` project applied Flyway V8 and reported every service healthy on host ports `55432/59092/18080/13000/19090/23001/18091`. The automated real API/Kafka/worker demo observed immutable `500` then `204` attempts, final `SUCCESS`, and exact +1 deltas for accepted event, delivery intent, retry, failed/success outcomes, and 5xx/2xx timers. PostgreSQL-down readiness returned 503, Kafka-down readiness remained UP, all resource limits were active, and 20,000-row PostgreSQL plans used the expected paging, retry, and outbox indexes. Isolated verification volumes were preserved until phase closure.
