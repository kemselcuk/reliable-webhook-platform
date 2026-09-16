# Reliable Webhook Platform

Reliable Webhook Platform is a local-first Spring Boot and React workspace for exploring durable, asynchronous webhook delivery. The long-term design uses PostgreSQL as the source of truth and Apache Kafka as the transport between durable work state and delivery workers.

Phase 1 currently provides the repository foundation and core PostgreSQL persistence:

- a Java 21 / Spring Boot 3.5.16 backend;
- a small React + TypeScript + Vite frontend;
- a backend system-health API at `GET /api/system/health`;
- a frontend health card with loading, healthy, and error states;
- PostgreSQL 17 and single-node Apache Kafka 4.3.1 Compose services;
- Dockerfiles, readiness health checks, and GitHub Actions checks;
- a Flyway-managed PostgreSQL schema for webhook endpoints, events, deliveries, and delivery attempts;
- Spring Data JPA repositories with JSONB event payload mapping and database constraints.

The REST endpoint/event APIs, transactional outbox, Kafka publisher/worker, retries, signing, and metrics are intentionally deferred to later phases. The health endpoint and persistence foundation are not a delivery guarantee.

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

The backend expects PostgreSQL at `localhost:5432` using the local defaults (`webhook` / `webhook` / `webhook-local-only`). Start the Compose PostgreSQL service first, or override `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` for another local database. Flyway applies versioned migrations on startup and Hibernate validates the mapped schema; Hibernate does not create or update tables.

The backend listens on `http://localhost:8080`. Check it with:

```bash
curl http://localhost:8080/api/system/health
curl http://localhost:8080/actuator/health/readiness
```

Compose exposes Kafka on two listener addresses for the later Kafka-enabled phases: containers use `kafka:29092` (the `INTERNAL` listener), while host tools use `localhost:${KAFKA_PORT:-9092}` (the `EXTERNAL` listener). Phase 1 starts Kafka as infrastructure but the backend does not yet create a Kafka client or connect to it. The backend connects to the Compose PostgreSQL service using the same database credentials through `SPRING_DATASOURCE_*` environment variables.

The Maven build separates `*Test` unit tests (Surefire) from `*IT` integration tests (Failsafe). The health HTTP slice does not require Docker or PostgreSQL. `CorePersistenceIT` uses a disposable PostgreSQL Testcontainer and verifies migrations, JSONB persistence, relationships, and database constraints. Run it with Docker available using `./mvnw -B -DskipUnitTests=true -Dit.test=CorePersistenceIT verify`.

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

### Full local Compose stack

```bash
docker compose up --build -d
docker compose ps
```

Open `http://localhost:3000`. Compose starts PostgreSQL and Kafka first, waits for their health checks, then starts the backend and waits for backend readiness before starting the frontend. Stop the stack with:

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

`.github/workflows/ci.yml` has separate backend and frontend jobs plus a foundation integration job. The backend job runs the build and Surefire unit tests with Failsafe `*IT` tests skipped. The integration job executes the Failsafe HTTP integration path independently and validates `docker compose config`; it does not require a live Docker daemon for the Spring test.

## Architecture and guarantees

See [docs/architecture.md](docs/architecture.md) for the target flow, failure boundaries, claim/lease direction, and the explicit guarantees that will be added phase by phase. In particular, external HTTP is outside the database transaction, so the target platform is at-least-once rather than exactly-once.

## Git workflow

Use a focused `feature/`, `fix/`, `refactor/`, or `docs/` branch for meaningful work. Keep commits small and explain the behavior they add. Run the relevant checks before pushing. The implementer does not merge directly to `main`; the repository manager reviews the diff, tests, documentation, and phase checklist before merging.

## Current limitations

There are no endpoint/event CRUD APIs, Kafka producers/consumers, retries, HMAC signatures, authentication, dashboards, or hosted deployment yet. Persistence is currently limited to the core schema and repositories; endpoint/event HTTP workflows are planned for the remainder of Phase 1.
