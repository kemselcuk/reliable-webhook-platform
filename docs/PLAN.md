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

- Current phase: Phase 3 — Kafka + delivery worker `[~]` in progress
- Overall status: Phases 0–2 `[x]` completed and merged; Phase 3 `[~]` started on `feature/phase-3-delivery-worker`
- Completed: atomic `Event + Delivery + OutboxEvent` persistence; compact keyed Kafka publishing; crash-safe delivery leases; token-guarded attempt completion; bounded, pooled HTTP delivery outside database transactions; PostgreSQL concurrency and WireMock transport tests
- Next: connect the worker to a controlled-concurrency Kafka consumer, then prove the complete asynchronous flow with PostgreSQL, Kafka, and WireMock
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

## Phase 3 — Kafka + delivery worker `[~]`

Features and tasks:

- [ ] Configure topic, producer, consumer group, partitions, keys, acknowledgements, and error handling
- [x] Implement atomic delivery claim/check and current-state load
- [x] Implement lease/token-based delivery claiming with a claim timeout and stale-claim recovery
- [x] Keep external HTTP outside database transactions/row locks and validate the lease token on completion
- [~] Implement pooled HTTP delivery with connect/response timeouts and controlled concurrency
- [x] Persist `DeliveryAttempt` and success/failure state
- [ ] Add WireMock/Testcontainers end-to-end tests

Acceptance criteria:

- [ ] Event submission reaches a WireMock webhook asynchronously through PostgreSQL, outbox, Kafka, and worker
- [ ] A 2xx response results in `SUCCESS` with a recorded attempt
- [ ] A crashed worker's expired lease is recovered, and a stale worker cannot overwrite a newer claim

## Phase 4 — Reliable retry `[ ]`

Features and tasks:

- [ ] Classify 2xx, 429/`Retry-After`, retryable 5xx/network failures, and permanent 4xx
- [ ] Persist attempt count, next retry time, and status
- [ ] Implement exponential backoff, jitter, max attempts, and injectable time/randomness
- [ ] Requeue due retries through the transactional outbox
- [ ] Implement `DEAD` transition and manual replay

Acceptance criteria:

- [ ] `500 → 500 → 200` reaches `SUCCESS` automatically
- [ ] Timeouts retry; permanent failures do not retry unnecessarily
- [ ] Max attempts result in `DEAD`; manual replay works

## Phase 5 — Idempotency + concurrency safety `[ ]`

Features and tasks:

- [ ] Implement API `Idempotency-Key`, canonical request hash, stored response reference, and unique constraint
- [ ] Reject reuse of a key with a different payload
- [ ] Harden atomic delivery claiming and duplicate Kafka message handling
- [ ] Add concurrent request and worker tests

Acceptance criteria:

- [ ] Concurrent identical keys create one logical event and return a consistent result
- [ ] Concurrent workers cannot actively process the same delivery
- [ ] Duplicate Kafka messages do not corrupt or repeat completed work

## Phase 6 — HMAC security `[ ]`

Features and tasks:

- [ ] Store a per-endpoint secret using a design that permits later rotation
- [ ] Sign raw body and timestamp with versioned HMAC-SHA256
- [ ] Send stable webhook/delivery IDs, timestamp, and signature headers
- [ ] Document verification and replay-tolerance window
- [ ] Prevent secret and sensitive-payload logging

Acceptance criteria:

- [ ] Valid signatures verify; body modification invalidates them
- [ ] Timestamp is part of the signed content and verification samples are documented/tested

## Phase 7 — Observability `[ ]`

Features and tasks:

- [ ] Add Actuator, Micrometer, Prometheus, and Grafana
- [ ] Add structured logs with event/delivery correlation and redaction
- [ ] Add bounded-cardinality throughput, outcome, retry, dead, latency, backlog, and consumer-lag metrics
- [ ] Add a useful local Grafana dashboard
- [ ] Reassess OpenTelemetry only if cross-boundary trace context adds demonstrable value

Acceptance criteria:

- [ ] Grafana shows system health and delivery behavior from local Compose
- [ ] Retry/failure/load scenarios visibly affect the expected metrics without high-cardinality labels

## Phase 8 — Frontend completion `[ ]`

Features and tasks:

- [ ] Complete endpoint management and event submission flows
- [ ] Add delivery list/detail and attempt history
- [ ] Add replay action for eligible deliveries
- [ ] Add a minimal system health/metrics view and clear API error states

Acceptance criteria:

- [ ] The complete platform demo workflow can be operated from a browser
- [ ] UI remains intentionally small and uses no paid or unnecessary heavy dependency

## Phase 9 — Hardening `[ ]`

Features and tasks:

- [ ] Complete critical integration/end-to-end and failure-path coverage
- [ ] Review query plans, indexes, locking, resource limits, and container health checks
- [ ] Finalize CI, Docker experience, security guidance, and docs
- [ ] Add focused architecture diagrams and README demo/use case
- [ ] Run the final failure-then-retry demo and verify metrics

Acceptance criteria:

- [ ] Full build and critical test suite pass from a clean checkout
- [ ] Final demo shows endpoint creation, event submission, failed attempt, retry, success, attempt history, and metric changes
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

No material deviation from the supplied implementation plan has been made.

Record future material changes as: `Planned`, `Implemented`, `Reason`, and `Trade-off`.

## Environment notes

- Requested implementer configuration is available and will be used: Luna with `xhigh` reasoning.
- The active root session cannot change its own model at runtime. It remains the manager/reviewer at the current environment-provided model and reasoning level; this is the closest available behavior to the requested Sol/high manager without silently substituting a spawned third role.
- Local tools detected at initialization: Java 21 is installed (Maven itself currently launches on Java 25), Maven 3.9.11, Node.js 22.23.2, npm 10.9.8, Docker 28.3.2, and Docker Compose 2.38.2.
- Phase 0 final verification used host-port overrides because local port `5432` was occupied. PostgreSQL, Kafka, backend, and frontend all reported healthy; direct backend health/readiness, frontend HTTP, and frontend-to-backend proxy requests returned HTTP 200. The frontend healthcheck was corrected to use `127.0.0.1` because the image resolved `localhost` to IPv6 while nginx listened on IPv4.
- Phase 1 final verification used a separate Compose project and fresh named volumes to prove clean Flyway startup without deleting existing local data. The default project volume in this workspace contains an earlier, unpublished V1 migration draft from implementation and therefore correctly fails Flyway checksum validation until that development-only volume is intentionally recreated.
- Phase 2 final verification used the separate `rwp-phase2-verify` Compose project with fresh volumes and host ports `56432/59095/18083/13003`. Normal publication and a live Kafka stop/start were exercised: the outage row remained durable as `PENDING/TIMEOUT`, recovered to `PUBLISHED`, and duplicate Kafka commands were observed as permitted by the documented at-least-once boundary.
