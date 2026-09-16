# Architecture direction

## Purpose and current boundary

The platform is designed as a small, fully local webhook delivery system. PostgreSQL will be the authoritative store for business state; Kafka will provide asynchronous transport, buffering, and horizontal consumption. A React UI will exercise the real HTTP API and show operational state without becoming a second source of truth.

Phase 1 now includes the PostgreSQL persistence foundation and REST/service increment: a Flyway-managed core schema, JPA mappings, repositories, endpoint registration/listing, and explicit event targeting to enabled endpoints. Kafka clients, the outbox, delivery workers, authentication, retries, and signing remain later-phase behavior; the target-flow sections below describe that approved direction rather than current guarantees.

## Current domain persistence

The V1 migration creates four core tables:

- `webhook_endpoints` stores a unique operator-facing name, destination URL, enabled state, and audit timestamps;
- `events` stores the event type, JSONB payload, and creation time;
- `deliveries` links one event to one endpoint, with a unique event/endpoint pair and initial durable status fields;
- `delivery_attempts` records numbered outcomes and enforces one row per delivery/attempt number.

The REST layer returns DTOs and an app-owned page shape. Endpoint URLs are normalized local URI values after validating absolute `http`/`https` scheme, host presence, and the absence of fragments/user-info. Event submission requires a non-empty, unique list of endpoint IDs and creates one `PENDING` delivery per selected enabled endpoint in one transaction. No broadcast or subscription behavior is implied.

Foreign keys and check constraints protect relationships, enum-compatible status values, attempt counts, HTTP status bounds, and attempt timestamp order. Hibernate validates this schema but does not create or update it. Endpoint secrets are intentionally absent from V1; their storage and signing lifecycle remain a Phase 6 security decision.

## Target event flow

```text
API request
    |
    v
PostgreSQL transaction: Event + Delivery + OutboxEvent
    |
    v
Polling publisher -> Kafka delivery command -> worker
                                      |
                                      v
                     lease/token claim in PostgreSQL
                                      |
                                      v
                         external webhook HTTP request
                                      |
                                      v
                     attempt + state transition in PostgreSQL
```

Creating an event currently persists the event and its delivery records in one database transaction. Phase 2 will add publish intent in that same transaction. A polling publisher will then move compact, versioned delivery references to Kafka. Workers will load current state from PostgreSQL, claim eligible work safely, perform bounded-concurrency HTTP delivery, and persist attempts and state transitions.

## Durability and delivery semantics

The outbox closes the failure window between business state and publish intent. If Kafka is unavailable, the outbox row remains pending and can be retried. A publisher can still publish a record and crash before marking its outbox row published; the same delivery command may therefore appear more than once. Worker-side state checks and atomic claims must make duplicate commands harmless.

External HTTP cannot participate in the PostgreSQL transaction. The target guarantee is at-least-once delivery: a receiver may observe a request again around worker or network failures. Stable event and delivery identifiers, idempotency guidance, and HMAC signing will help receivers handle that contract. Exactly-once delivery is not claimed.

## Worker claiming and crash recovery

Phase 3 worker claiming must be lease/token based. A claim records an owner token and an expiry/claim timeout; completing or failing work must verify the token so a stale worker cannot overwrite a newer claim. A worker crash or lost heartbeat must allow a later worker to recover an expired claim and continue processing. This is required to prevent deliveries remaining `PROCESSING` forever.

The external HTTP call must not run while a database transaction or row lock is held open. Claiming and completion are short database operations around the external call: acquire a lease, release the transaction, perform HTTP, then persist the result only if the lease token is still valid. The implementation should test stale-claim recovery and late completion explicitly.

## Retry and failure direction

Later phases will classify HTTP outcomes into success, retryable, and permanent failure. Retry state (attempt count, next retry time, and terminal status) belongs in PostgreSQL. Due retries will re-enter the outbox rather than relying on an in-memory timer or `Thread.sleep`. Backoff, jitter, maximum attempts, and an operator replay action will be injectable and testable.

## Operational boundaries

Actuator health endpoints are available in Phase 0. Later observability work should add bounded-cardinality metrics and structured, redacted logs. Event IDs, delivery IDs, full URLs, secrets, and payloads must not become metric labels or accidental log content. PostgreSQL and Kafka remain local and free to run; no hosted service is required.

## Kafka bootstrap addresses

Compose configures separate Kafka listeners so container clients and host tools receive usable advertised addresses:

- `kafka:29092` is the `INTERNAL` bootstrap address for services on the Compose network (the future backend publisher and worker);
- `localhost:${KAFKA_PORT:-9092}` is the `EXTERNAL` bootstrap address for host-side Kafka tools, using the published port.

The controller listener remains internal to the single-node KRaft broker. Phase 0 starts this broker as local infrastructure, but the backend has no Kafka client and does not connect to it yet.

## Local topology

Compose defines four services for the local topology:

- PostgreSQL 17-alpine with a named data volume;
- official Apache Kafka 4.3.1 in single-node KRaft mode with a named data volume;
- the Spring Boot backend, built as a non-root Java runtime image;
- the React build served by an unprivileged nginx image, proxying `/api` to the backend.

The backend and frontend health checks use readiness/HTTP endpoints. Compose dependencies wait for infrastructure and backend health. The Phase 1 backend connects to PostgreSQL, runs Flyway, and includes database health in readiness; it still has no Kafka client.

## Phase boundaries

Phase 1 introduces domain records, versioned migrations, and basic endpoint/event APIs. Phase 2 adds the transactional outbox and publisher. Phase 3 adds Kafka commands, lease-based workers, and external delivery. Phases 4–7 add retries, idempotency/concurrency hardening, HMAC security, and observability. Phase 8 completes the UI and Phase 9 hardens the end-to-end demo.
