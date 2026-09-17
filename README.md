# Reliable Webhook Platform

Reliable Webhook Platform is a local-first Spring Boot and React workspace for exploring durable, asynchronous webhook delivery. The long-term design uses PostgreSQL as the source of truth and Apache Kafka as the transport between durable work state and delivery workers.

Phase 2 currently provides the repository foundation, core PostgreSQL persistence, and a transactional outbox publisher:

- a Java 21 / Spring Boot 3.5.16 backend;
- a small React + TypeScript + Vite frontend;
- a backend system-health API at `GET /api/system/health`;
- a frontend health card with loading, healthy, and error states;
- PostgreSQL 17 and single-node Apache Kafka 4.3.1 Compose services;
- Dockerfiles, readiness health checks, and GitHub Actions checks;
- a Flyway-managed PostgreSQL schema for webhook endpoints, events, deliveries, and delivery attempts;
- Spring Data JPA repositories with JSONB event payload mapping and database constraints;
- atomic `Event + Delivery + OutboxEvent` creation in one PostgreSQL transaction;
- a lease/token-based polling publisher that sends compact delivery commands to Kafka;
- a minimal browser workflow for endpoint registration/listing and event submission to selected endpoints.

The Kafka delivery worker, webhook retries, signing, and metrics remain later-phase work. The current REST flow durably records publish intent and asynchronously publishes a delivery reference to Kafka, but it does not send an external webhook yet.

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

No paid service, cloud account, or real secret is needed. Copy `.env.example` to `.env` only when you want to override the documented local defaults; the checked-in example contains local development credentials only.

## Run the foundation locally

### Backend

```bash
cd backend
./mvnw verify
./mvnw spring-boot:run
```

The backend expects PostgreSQL at `localhost:5432` and Kafka at `localhost:9092` using the local defaults. Start both Compose infrastructure services first, or override the `SPRING_DATASOURCE_*` and `SPRING_KAFKA_BOOTSTRAP_SERVERS` settings. Flyway applies versioned migrations on startup and Hibernate validates the mapped schema; Hibernate does not create or update tables. Set `WEBHOOK_OUTBOX_PUBLISHER_ENABLED=false` only when intentionally running the API without publication.

The backend listens on `http://localhost:8080`. Check it with:

```bash
curl http://localhost:8080/api/system/health
curl http://localhost:8080/actuator/health/readiness
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

### REST API and Phase 2 outbox flow

Create and list webhook endpoints:

```bash
curl -i -X POST http://localhost:8080/api/webhook-endpoints \
  -H 'Content-Type: application/json' \
  -d '{"name":"Orders","url":"http://localhost:8081/webhooks"}'
curl 'http://localhost:8080/api/webhook-endpoints?page=0&size=20'
```

Submit an event to explicitly selected, enabled endpoint IDs returned by the endpoint API:

```bash
curl -i -X POST http://localhost:8080/api/events \
  -H 'Content-Type: application/json' \
  -d '{"type":"order.created","payload":{"orderId":"order-123"},"endpointIds":["<endpoint-uuid>"]}'
```

Endpoint creation returns `201 Created` with a `Location` header. Event creation atomically stores the event, one `PENDING` delivery per selected endpoint, and one `PENDING` outbox command per delivery. The polling publisher claims due outbox rows, publishes `{"version":1,"deliveryId":"..."}` using the delivery ID as Kafka key, and marks acknowledged rows `PUBLISHED`. Invalid requests and endpoint lookup/state failures use RFC 9457 `application/problem+json` responses with a stable `code` property. API idempotency and authentication remain later-phase concerns.

### Full local Compose stack

```bash
docker compose up --build -d
docker compose ps
```

Open `http://localhost:3000`. From the UI, create an endpoint, select it in the event form, and submit the sample JSON payload. Phase 2 stores the event, delivery, and outbox intent, then publishes the compact command to Kafka. The delivery itself remains `PENDING` until the Phase 3 worker exists.

Inspect recent outbox state:

```bash
docker compose exec postgres psql -U webhook -d webhook -c \
  "select delivery_id,status,publish_attempts,last_error,created_at from outbox_events order by created_at desc limit 10;"
```

Read published commands from the beginning of the topic:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:29092 \
  --topic webhook.delivery.commands.v1 \
  --from-beginning \
  --property print.key=true \
  --property key.separator=' => ' \
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

See [docs/architecture.md](docs/architecture.md) for the target flow, failure boundaries, claim/lease direction, and the explicit guarantees that will be added phase by phase. In particular, external HTTP is outside the database transaction, so the target platform is at-least-once rather than exactly-once.

## Git workflow

Use a focused `feature/`, `fix/`, `refactor/`, or `docs/` branch for meaningful work. Keep commits small and explain the behavior they add. Run the relevant checks before pushing. The implementer does not merge directly to `main`; the repository manager reviews the diff, tests, documentation, and phase checklist before merging.

## Current limitations

There is no Kafka consumer/delivery worker, external webhook HTTP call, delivery retry policy, HMAC signing, authentication, dashboard, or hosted deployment yet. Phase 2 publishes delivery commands at least once: a crash after Kafka acknowledgement but before the PostgreSQL `PUBLISHED` update can cause the same stable delivery command to be published again. Phase 3 worker state checks and atomic claims will make those duplicate commands harmless.
