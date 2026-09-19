# Reliable Webhook Platform

Reliable Webhook Platform is a local-first Spring Boot and React workspace for exploring durable, asynchronous webhook delivery. The long-term design uses PostgreSQL as the source of truth and Apache Kafka as the transport between durable work state and delivery workers.

Phase 8 currently provides the repository foundation, durable retry processing, manual replay, concurrency-safe asynchronous delivery, versioned HMAC webhook signing, local observability, and a small browser operations workflow:

- a Java 21 / Spring Boot 3.5.16 backend;
- a small React + TypeScript + Vite frontend;
- backend system-health and bounded summary APIs at `GET /api/system/health` and `GET /api/system/summary`;
- a frontend health card with loading, healthy, and error states;
- PostgreSQL 17 and single-node Apache Kafka 4.3.1 Compose services;
- Dockerfiles, readiness health checks, and GitHub Actions checks;
- a Flyway-managed PostgreSQL schema for webhook endpoints, rotation-ready signing secrets, events, deliveries, and delivery attempts;
- Spring Data JPA repositories with JSONB event payload mapping and database constraints;
- atomic `Event + Delivery + OutboxEvent` creation in one PostgreSQL transaction;
- a lease/token-based polling publisher that sends compact delivery commands to Kafka;
- a lease/token-based delivery worker that claims work in PostgreSQL and sends bounded HTTP requests outside database transactions;
- a manual-acknowledgment Kafka listener with duplicate-command state checks and bounded worker concurrency;
- bounded failure classification for successful responses, permanent HTTP failures, retryable 429/5xx responses, and transport failures;
- durable exponential backoff with configurable jitter, maximum attempts, and `Retry-After` support;
- PostgreSQL retry state and a polling scheduler that requeues due deliveries through the transactional outbox;
- `DEAD` transition after the retry budget is exhausted and an atomic manual replay API for terminal failures;
- PostgreSQL, Kafka, and WireMock integration coverage for the complete asynchronous API-to-webhook flow;
- a minimal browser workflow for endpoint registration/listing (including one-time secret entry), safe enable/disable, idempotent event submission, delivery browsing, attempt inspection, and eligible replay.
- Actuator health/readiness/info and Prometheus endpoints with safe health details;
- ECS structured console logs with event/delivery correlation and payload/secret redaction;
- bounded-cardinality delivery, retry, backlog, HTTP latency, and Kafka lag metrics;
- local Prometheus and Grafana Compose services with a provisioned delivery dashboard.

Authentication and secret rotation remain intentionally deferred. The current REST flow durably records publish intent, publishes a compact delivery reference to Kafka, and asynchronously sends the event payload to the configured webhook endpoint with a versioned HMAC signature. See [docs/observability.md](docs/observability.md) for metric names, redaction boundaries, local monitoring, and the OpenTelemetry decision.

## Repository layout

```text
backend/       Spring Boot application, Maven wrapper, unit and HTTP integration tests
frontend/      Vite React/TypeScript application and its production nginx image
docs/          living plan and architecture direction
compose.yaml   local PostgreSQL, Kafka, backend, and frontend stack
.github/       CI workflow
```

## Requirements

- Java 21
- Node.js 22 and npm 10 (or compatible current LTS versions)
- Docker Engine/Desktop with Compose v2

No paid service or cloud account is needed. Endpoint signing secrets are local application data; use a distinct development value and do not commit real production secrets. Copy `.env.example` to `.env` only when you want to override the documented local defaults; the checked-in example contains local development credentials only.

## Run the foundation locally

### Backend

```bash
cd backend
./mvnw verify
./mvnw spring-boot:run
```

The backend expects PostgreSQL at `localhost:5432` and Kafka at `localhost:9092` using the local defaults. Start both Compose infrastructure services first, or override the `SPRING_DATASOURCE_*` and `SPRING_KAFKA_BOOTSTRAP_SERVERS` settings. Flyway applies versioned migrations on startup and Hibernate validates the mapped schema; Hibernate does not create or update tables. A host-run backend must set `WEBHOOK_DELIVERY_WORKER_ENABLED=true` to consume commands and deliver webhooks; Compose enables the worker automatically. Set `WEBHOOK_OUTBOX_PUBLISHER_ENABLED=false` only when intentionally running the API without publication.

The backend listens on `http://localhost:8080`. Check it with:

```bash
curl http://localhost:8080/api/system/health
curl http://localhost:8080/actuator/health/readiness
curl http://localhost:8080/actuator/prometheus
```

Compose exposes Kafka on two listener addresses: containers use `kafka:29092` (the `INTERNAL` listener), while host tools use `localhost:${KAFKA_PORT:-9092}` (the `EXTERNAL` listener). The Compose backend uses the internal address; a backend started directly on the host uses the external address. The backend connects to the Compose PostgreSQL service using the same database credentials through `SPRING_DATASOURCE_*` environment variables.

The Maven build separates `*Test` unit tests (Surefire) from `*IT` integration tests (Failsafe). The integration suite uses disposable PostgreSQL and Kafka Testcontainers for schema, transaction, concurrent claim, recovery, and real publish verification. Run the complete suite with Docker available using `./mvnw verify`; run only unit tests with `./mvnw -DskipITs=true test`.

### Frontend development server

```bash
cd frontend
npm ci
npm run lint
npm run typecheck
npm test
npm run build
npm run dev
```

Vite serves the UI at `http://localhost:5173` and proxies `/api` to the backend at port 8080.

### REST API and asynchronous delivery flow

Create and list webhook endpoints:

```bash
curl -i -X POST http://localhost:8080/api/webhook-endpoints \
  -H 'Content-Type: application/json' \
  -d '{"name":"Orders","url":"http://localhost:8081/webhooks","secret":"replace-with-at-least-32-bytes-of-secret-material"}'
curl 'http://localhost:8080/api/webhook-endpoints?page=0&size=20'
```

Submit an event to explicitly selected, enabled endpoint IDs returned by the endpoint API:

```bash
curl -i -X POST http://localhost:8080/api/events \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-123-create' \
  -d '{"type":"order.created","payload":{"orderId":"order-123"},"endpointIds":["<endpoint-uuid>"]}'
```

Endpoint creation returns `201 Created` with a `Location` header. The caller-supplied signing secret must be between 32 and 512 UTF-8 bytes; it is preserved exactly, stored only in the separate rotation-ready secret table, and never returned by the API. Event creation atomically stores the event, one `PENDING` delivery per selected endpoint, one `PENDING` outbox command per delivery, and (when supplied) the idempotency record. Reusing an `Idempotency-Key` with the same logical request, including concurrently, returns the originally stored `201` response without creating more rows. Request object-key order, numeric formatting, endpoint selection order, and surrounding event-type whitespace do not change the logical request hash. Reusing a key with a different request returns an RFC 9457 `409 Conflict` response with code `IDEMPOTENCY_KEY_CONFLICT`; concurrent requests using the same key are serialized by a transaction-scoped PostgreSQL advisory lock. The polling publisher claims due outbox rows, publishes `{"version":1,"deliveryId":"..."}` using the delivery ID as Kafka key, and marks acknowledged rows `PUBLISHED`. The enabled worker validates the command key/version, atomically claims the current delivery lease, loads the current JSONB payload, endpoint, and active signing key, sends the request outside the database transaction, and records the token-guarded outcome. Concurrent workers cannot actively process the same delivery, and a duplicate command received after terminal completion is acknowledged without another HTTP request. Invalid requests and endpoint lookup/state failures use RFC 9457 `application/problem+json` responses with a stable `code` property.

The worker sends the JSONB-derived payload as `application/json` and requests JSON responses. JSON semantics are preserved, but the original request's whitespace and object-key byte order are not promised. It sends these stable headers:

- `X-Webhook-Id`: event UUID;
- `X-Delivery-Id`: delivery UUID;
- `X-Webhook-Event`: event type.
- `X-Webhook-Timestamp`: ASCII Unix seconds used in the signature input;
- `X-Webhook-Signature`: `v1=<lowercase-hex-HMAC-SHA256>`;
- `X-Webhook-Key-Id`: bounded active key identifier, currently `v1`.

A `2xx` response becomes `SUCCESS`. `429`, `5xx`, and bounded transport failures are retryable; other non-2xx responses, including redirects and permanent `4xx` responses, become `FAILED` without unnecessary retry.

The exact signing input is the ASCII Unix-seconds timestamp, a literal `.`, and the exact UTF-8 request body bytes:

```text
ASCII(str(timestamp_seconds)) + b"." + raw_body_bytes
```

Receivers must verify the signature over the raw body before parsing or reserializing JSON, compare the expected and supplied values in constant time, and reject timestamps outside a recommended five-minute replay-tolerance window. See [docs/security.md](docs/security.md) for receiver verification pseudocode, a runnable Python sample, secret-storage caveats, and the rotation/key-ID boundary.

### Durable retry and replay

Retry state is authoritative in PostgreSQL. Each completed run increments the lifetime `attempt_count` and the current run's `run_attempt_count`; `RETRY_SCHEDULED` also stores `next_retry_at`. The default policy is five attempts, a one-second initial delay, a one-hour maximum delay, and multiplicative jitter of `0.20`:

```text
WEBHOOK_DELIVERY_RETRY_MAX_ATTEMPTS=5
WEBHOOK_DELIVERY_RETRY_INITIAL_DELAY=PT1S
WEBHOOK_DELIVERY_RETRY_MAX_DELAY=PT1H
WEBHOOK_DELIVERY_RETRY_JITTER_FACTOR=0.20
```

Backoff is exponential and capped. A valid `Retry-After` delta-seconds value or RFC 1123 HTTP-date is used as a minimum delay and remains bounded by the configured maximum; malformed, negative, or past values fall back to policy backoff. The implementation follows [RFC 9110 Retry-After](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3). No retry uses `Thread.sleep` or memory-only scheduling.

Compose enables the durable retry scheduler automatically. Its settings can be changed with `WEBHOOK_DELIVERY_RETRY_SCHEDULER_ENABLED`, `WEBHOOK_DELIVERY_RETRY_SCHEDULER_POLL_INTERVAL` (default `PT1S`), and `WEBHOOK_DELIVERY_RETRY_SCHEDULER_BATCH_SIZE` (default `50`). Due `RETRY_SCHEDULED` rows are atomically changed to `PENDING` and receive a fresh compact outbox command in the same PostgreSQL transaction; Kafka is never written directly by the scheduler.

Only `FAILED` and `DEAD` deliveries whose endpoint remains enabled can be replayed:

```bash
curl -i -X POST \
  http://localhost:8080/api/deliveries/<delivery-uuid>/replay
```

The response is `202 Accepted` with the delivery ID, `PENDING` status, lifetime attempt count, and reset current-run count. Replay preserves the delivery-attempt history and lifetime counter, resets only `run_attempt_count`, clears retry/lease state, and creates one new outbox command. A concurrent replay request receives a conflict rather than creating duplicate work.

Delivery browser reads are available at `GET /api/deliveries?page=0&size=20&status=FAILED` and `GET /api/deliveries/<delivery-uuid>`. The list is bounded and ordered by `(created_at, id)`; detail returns endpoint/event metadata, durable status/counters/timestamps, and immutable attempts in attempt-number order. Payloads and signing secrets are deliberately omitted. `GET /api/system/summary` returns bounded durable counts plus process-lifetime Micrometer accepted-event/delivery-intent counters for the local operations view.

### Full local Compose stack

```bash
docker compose up --build -d
docker compose ps
```

Open `http://localhost:3000`. From the UI, create or enable an endpoint, select it in the event form, optionally provide an idempotency key, and submit the sample JSON payload. The first resulting delivery is selected automatically; use the delivery browser to inspect status/attempts and replay only `FAILED` or `DEAD` deliveries. The platform stores the event, delivery, and outbox intent, then publishes and consumes the compact command asynchronously. A successful webhook becomes `SUCCESS`; retryable failures move through `RETRY_SCHEDULED` and back to `PENDING`, while exhausted retries become `DEAD`.

Prometheus is available at `http://localhost:9090` and Grafana at
`http://localhost:3001` with the provisioned **Reliable Webhook Platform
Overview** dashboard. Grafana uses `admin` / `admin-local-only` by default for
the isolated local stack; override `GRAFANA_ADMIN_USER` and
`GRAFANA_ADMIN_PASSWORD` when needed. See [docs/observability.md](docs/observability.md)
for metric labels and redaction guarantees.

Inspect recent outbox state:

```bash
docker compose exec postgres psql -U webhook -d webhook -c \
  "select delivery_id,status,publish_attempts,last_error,created_at from outbox_events order by created_at desc limit 10;"
```

Inspect delivery and attempt state:

```bash
docker compose exec postgres psql -U webhook -d webhook -c \
  "select id,event_id,webhook_endpoint_id,status,attempt_count,run_attempt_count,next_retry_at,claim_token,claimed_at,updated_at from deliveries order by created_at desc limit 10;"
docker compose exec postgres psql -U webhook -d webhook -c \
  "select delivery_id,attempt_number,outcome,http_status,error_code,started_at,completed_at from delivery_attempts order by completed_at desc limit 20;"
```

Read published commands from the beginning of the topic:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:29092 \
  --topic webhook.delivery.commands.v1 \
  --from-beginning \
  --formatter-property print.key=true \
  --formatter-property key.separator=' => ' \
  --timeout-ms 10000
```

Compose starts PostgreSQL and Kafka first, waits for their health checks, then starts the backend and frontend. Stop the stack with:

```bash
docker compose down
```

If one of the default host ports is already in use, override only the host-side mappings without changing the container topology. For example:

```bash
POSTGRES_PORT=55432 KAFKA_PORT=59092 BACKEND_PORT=18080 FRONTEND_PORT=13000 \
  docker compose up --build -d
```

The same values can be placed in a local `.env` copied from `.env.example`. With the example overrides above, open `http://localhost:13000`.

Named volumes preserve local PostgreSQL and Kafka data. To remove those local volumes intentionally, use `docker compose down --volumes`.

Validate the rendered Compose model without starting containers:

```bash
docker compose config
```

## CI checks

`.github/workflows/ci.yml` has separate backend and frontend jobs plus an integration job. The backend job runs the build and Surefire unit tests with Failsafe `*IT` tests skipped. The integration job uses the runner's Docker daemon for PostgreSQL/Kafka Testcontainers, executes the Failsafe suite, and validates `docker compose config`.

## Architecture and guarantees

See [docs/architecture.md](docs/architecture.md) for the current flow, failure boundaries, claim/lease behavior, and explicit guarantees. External HTTP is outside the database transaction, so delivery is at-least-once rather than exactly-once.

## Git workflow

Use a focused `feature/`, `fix/`, `refactor/`, or `docs/` branch for meaningful work. Keep commits small and explain the behavior they add. Run the relevant checks before pushing. The implementer does not merge directly to `main`; the repository manager reviews the diff, tests, documentation, and phase checklist before merging.

## Current limitations

Authentication (beyond webhook signing), secret rotation, and hosted deployment remain later or intentionally deferred work. The outbox and worker are both at-least-once: a crash around Kafka acknowledgement or external HTTP completion can produce duplicate commands or requests. Lease expiry and claim tokens prevent stale workers from overwriting newer state, and a duplicate command received after `SUCCESS` is terminal and does not resend the request. HTTP connect/response timeouts and worker concurrency are bounded by `webhook.delivery.worker` properties.
