# Architecture direction

## Purpose and current boundary

The platform is a small, fully local webhook delivery system. PostgreSQL is authoritative for business state; Kafka provides asynchronous transport, buffering, and horizontal consumption. A React UI exercises the real HTTP API and shows operational state without becoming a second source of truth.

Phase 4 includes the PostgreSQL persistence foundation, REST/browser event flow, transactional outbox, Kafka publisher, bounded delivery worker, durable retry policy, due-retry requeueing, and manual replay. The first Phase 5 increment adds API idempotency for event submission: a canonical request hash and stored response reference are persisted with a unique `Idempotency-Key`. Event creation records business state, publish intent, and the idempotency record atomically; the publisher sends compact delivery commands and the worker claims and delivers them asynchronously. Concurrent-request and duplicate-message hardening, authentication, HMAC signing, and observability remain later-phase behavior.

## Current domain persistence

The V1 migration creates four core tables:

- `webhook_endpoints` stores a unique operator-facing name, destination URL, enabled state, and audit timestamps;
- `events` stores the event type, JSONB payload, and creation time;
- `deliveries` links one event to one endpoint, with a unique event/endpoint pair and initial durable status fields;
- `delivery_attempts` records numbered outcomes and enforces one row per delivery/attempt number.

The REST layer returns DTOs and an app-owned page shape. Endpoint URLs are normalized local URI values after validating absolute `http`/`https` scheme, host presence, and the absence of fragments/user-info. Event submission requires a non-empty, unique list of endpoint IDs and creates one `PENDING` delivery per selected enabled endpoint in one transaction. No broadcast or subscription behavior is implied.

Foreign keys and check constraints protect relationships, enum-compatible status values, attempt counts, HTTP status bounds, and attempt timestamp order. Hibernate validates this schema but does not create or update it. Endpoint secrets are intentionally absent from V1; their storage and signing lifecycle remain a Phase 6 security decision.

The append-only V2 migration adds `outbox_events`. Each row references a delivery and stores the compact JSONB command, availability time, `PENDING`/`CLAIMED`/`PUBLISHED` state, claim token/timestamp, publish-attempt count, bounded failure category, and audit timestamps. Check constraints enforce coherent lease/published fields, while partial indexes support due work and stale-claim scans.

The append-only V3 migration adds `claim_token` and `claimed_at` to `deliveries`. A check requires both lease fields for `PROCESSING` and neither field for every other status; a partial `(claimed_at, id)` index supports stale `PROCESSING` claims. Delivery claim and completion transitions are token guarded; a stale worker can neither change state nor insert an attempt after its lease has been reclaimed.

The append-only V4 migration adds `run_attempt_count` and retry-state coherence to `deliveries`. `attempt_count` is the monotonic lifetime count used to number the complete attempt history; `run_attempt_count` counts attempts since the latest manual replay and is reset only by replay. Both counters are non-negative, and the run count cannot exceed the lifetime count. `RETRY_SCHEDULED` requires a non-null `next_retry_at`; every other delivery status requires it to be null. These constraints keep retry and replay state durable and mutually coherent.

The append-only V5 migration adds `event_idempotency_keys`. Each row stores the opaque key, a SHA-256 hash of the canonical supported event request, the created event reference, and the original response JSON. The key is unique, so sequential reuse can return the same logical event and response without creating duplicate events, deliveries, or outbox commands; a hash mismatch is reported as `IDEMPOTENCY_KEY_CONFLICT`.

## Implemented event flow

```text
API request
    |
    v
PostgreSQL transaction: Event + Delivery + OutboxEvent + IdempotencyKey (when supplied)
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

Creating an event now persists the event, deliveries, and one outbox command per delivery in one database transaction. The polling publisher moves compact, versioned delivery references to Kafka. The Phase 4 worker validates the command and key, loads current state from PostgreSQL, claims eligible delivery work safely, performs bounded-concurrency HTTP delivery, and persists token-guarded attempts and state transitions.

## Durability and delivery semantics

The outbox closes the failure window between business state and publish intent. A publisher claims a bounded batch in a short PostgreSQL transaction using `FOR UPDATE SKIP LOCKED`, commits that claim, publishes outside the database transaction, and then performs a token-guarded state update. If Kafka is unavailable or acknowledgement times out, the row returns to `PENDING` with a durable next availability time and bounded failure category. Crashed `CLAIMED` rows become eligible again after the claim timeout.

A publisher can still publish a record and crash before marking its outbox row `PUBLISHED`; the same delivery command may therefore appear more than once. Producer idempotence helps with producer-level retries but does not make the PostgreSQL/Kafka boundary atomic. Worker-side state checks and atomic claims must make duplicate commands harmless.

The Kafka contract is topic `webhook.delivery.commands.v1`, key `deliveryId`, and value `{"version":1,"deliveryId":"..."}`. The small reference keeps PostgreSQL authoritative; consumers load current business state instead of treating Kafka payloads as a second database. The local topic has three partitions, so commands for one delivery retain key-based partition ordering while different deliveries can be processed in parallel.

The listener uses String deserializers, manual-immediate acknowledgments, and the configured worker group/concurrency. `SUCCESS`, `FAILED`, terminal, not-found, disabled, ineligible, and stale-completion outcomes are acknowledged. A fresh `PROCESSING` lease is nacked with a short bounded delay so redelivery can recover after lease expiry; malformed, unsupported, and key-mismatched commands are acknowledged as bounded poison input with only topic/partition/offset/category logged. Unexpected listener or infrastructure failures use a bounded fixed-backoff error handler with effectively unlimited attempts.

External HTTP cannot participate in the PostgreSQL transaction. The implemented guarantee is at-least-once delivery: a receiver may observe a request again around worker or network failures. Stable event and delivery identifiers and idempotency guidance help receivers handle that contract; HMAC signing is deferred to Phase 6. Exactly-once delivery is not claimed.

## Retry state and failure classification

The worker records one bounded attempt for every completed HTTP or transport exchange. `2xx` is `SUCCESS`. `429`, `500`–`599`, and transport failures are retryable; retryable failures below the configured maximum become `RETRY_SCHEDULED` with `next_retry_at`, while the maximum attempt becomes `DEAD`. Other non-2xx responses, including redirects and other `4xx` responses, are permanent `FAILED` outcomes. A retry decision carries only a bounded error code such as `HTTP_503` or `TIMEOUT`; response bodies and sensitive headers are not persisted or logged.

The default retry policy uses five attempts, exponential initial delay `PT1S`, maximum delay `PT1H`, and jitter factor `0.20`. Jitter is injectable for deterministic tests. A valid `Retry-After` delta-seconds value or RFC 1123 HTTP-date acts as a minimum delay and is capped by the configured maximum. Invalid, negative, or past metadata falls back to exponential backoff. See [RFC 9110 §10.2.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3) for the HTTP semantics.

The retry scheduler selects due `RETRY_SCHEDULED` deliveries for enabled endpoints with `FOR UPDATE SKIP LOCKED`, ordered by `next_retry_at` and ID. In one PostgreSQL transaction it changes each row to `PENDING`, clears `next_retry_at`, and inserts a fresh `PENDING` `DELIVERY_REQUESTED` outbox command. It never calls Kafka directly, so a Kafka outage cannot lose retry intent or create a database/Kafka dual-write window.

The durable status meanings are:

- `SUCCESS`: the latest completed delivery attempt returned `2xx`;
- `RETRY_SCHEDULED`: a retryable failure is waiting for `next_retry_at`;
- `FAILED`: a permanent failure completed, or a delivery is eligible for manual replay;
- `DEAD`: the retry budget was exhausted and the delivery requires manual replay;
- `PENDING`/`PROCESSING`: queued or actively claimed work.

Manual replay locks the delivery and endpoint row, accepts only enabled `FAILED` or `DEAD` deliveries, changes the delivery to `PENDING`, clears retry/lease state, resets `run_attempt_count`, preserves lifetime `attempt_count` and every existing `delivery_attempts` row, and inserts one new outbox command in the same transaction. Concurrent replay requests therefore produce at most one accepted transition/outbox command.

## Worker claiming and crash recovery

Phase 3 worker claiming is lease/token based. A claim records an owner token and claim time; completing or failing work verifies the token so a worker that outlives its lease cannot overwrite a newer worker's state. A worker crash or lost heartbeat allows a later worker to recover an expired claim. A duplicate command for a `SUCCESS` or other terminal delivery is read as terminal and does not trigger another HTTP request. Retry and replay transitions also clear lease fields defensively before new work is published.

The external HTTP call must not run while a database transaction or row lock is held open. Claiming and completion are short database operations around the external call: acquire a lease, release the transaction, perform HTTP, then persist the result only if the lease token is still valid. The worker sends the JSONB-derived payload as `application/json` (semantic JSON is preserved, but source whitespace/key byte order is not promised) with `Accept: application/json` and stable `X-Webhook-Id` (event UUID), `X-Delivery-Id`, and `X-Webhook-Event` headers. Connect/response timeouts and worker concurrency are bounded by `webhook.delivery.worker`; response bodies are not retained or logged.

## Operational boundaries

Actuator health endpoints are available in Phase 0. Later observability work should add bounded-cardinality metrics and structured, redacted logs. Event IDs, delivery IDs, full URLs, secrets, and payloads must not become metric labels or accidental log content. PostgreSQL and Kafka remain local and free to run; no hosted service is required.

## Kafka bootstrap addresses

Compose configures separate Kafka listeners so container clients and host tools receive usable advertised addresses:

- `kafka:29092` is the `INTERNAL` bootstrap address for services on the Compose network (the backend publisher and worker);
- `localhost:${KAFKA_PORT:-9092}` is the `EXTERNAL` bootstrap address for host-side Kafka tools, using the published port.

The controller listener remains internal to the single-node KRaft broker. The backend uses producer acknowledgements set to `all`, producer idempotence, and a manual-immediate worker listener acknowledgment. Topic creation is declarative and broker unavailability is not made fatal to application context startup; unpublished intent remains in PostgreSQL.

## Local topology

Compose defines four services for the local topology:

- PostgreSQL 17-alpine with a named data volume;
- official Apache Kafka 4.3.1 in single-node KRaft mode with a named data volume;
- the Spring Boot backend, built as a non-root Java runtime image;
- the React build served by an unprivileged nginx image, proxying `/api` to the backend.

The backend and frontend health checks use readiness/HTTP endpoints. Compose dependencies wait for infrastructure and backend health. The Phase 4 backend connects to PostgreSQL and Kafka, runs Flyway, and starts the outbox polling publisher, delivery worker, and durable retry scheduler after the broker is healthy in Compose. Compose enables all three; a host-run backend must set `WEBHOOK_DELIVERY_WORKER_ENABLED=true` and `WEBHOOK_DELIVERY_RETRY_SCHEDULER_ENABLED=true` when retry processing is desired.

## Phase boundaries

Phase 1 introduces domain records, versioned migrations, and basic endpoint/event APIs. Phase 2 adds the transactional outbox and publisher. Phase 3 adds Kafka commands, lease-based workers, bounded external delivery, and the complete asynchronous integration flow. Phase 4 adds reliable retries, durable requeueing, `DEAD`, and manual replay; Phase 5 adds API idempotency/concurrency hardening; Phase 6 adds HMAC security; and Phase 7 adds observability. Phase 8 completes the UI and Phase 9 hardens the end-to-end demo.
