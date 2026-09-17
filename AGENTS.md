# Reliable Webhook Platform — Agent Working Agreement

This file governs agent work across the repository. Product scope, phase status, acceptance criteria, completed work, next work, blockers, and material decision changes are tracked in [`docs/PLAN.md`](docs/PLAN.md), which is the development-plan source of truth.

## Session recovery

At the beginning of every work session:

1. Read this file and `docs/PLAN.md` completely.
2. Inspect the current branch, worktree, recent commits, and configured remote.
3. Preserve all existing user and agent work; never overwrite or discard unexplained changes.
4. Reconstruct the active feature and its next unchecked acceptance criterion from Git and `docs/PLAN.md`.
5. Continue the active phase before starting a later phase unless the plan records an explicit blocker or approved deviation.

## Roles and decision chain

### Main agent — manager/reviewer

The main agent owns:

- architecture and significant technical decisions;
- feature scope and acceptance criteria;
- `docs/PLAN.md` accuracy;
- bounded task assignment to the implementer;
- review of code, migrations, tests, configuration, and documentation;
- build/test and acceptance evaluation;
- branch creation, commits, pushes, merges, and CI verification;
- the decision that a feature or phase is complete.

The main agent should delegate implementation work where practical and retain architecture, review, debugging direction, and Git lifecycle responsibility.

### Subagent — implementer

Use one `gpt-5.6-luna` implementer with `xhigh` reasoning when the environment supports it. Give it one concrete, bounded task with explicit files or scope, constraints, and acceptance criteria. Suitable work includes Java/Spring, React/TypeScript, tests, migrations, Docker/configuration, and small scoped refactors.

The implementer must:

- work only inside the assigned feature scope and current feature branch;
- read this file and relevant plan/docs before editing;
- preserve unrelated work;
- report assumptions, changed files, verification performed, and remaining risks;
- ask the manager before making a significant architecture, security, reliability, scope, or technology decision;
- never merge to `main`, rewrite shared history, or perform destructive Git operations.

Decision escalation is `implementer -> main agent -> user`. The manager resolves ordinary implementation questions from requirements and existing decisions. Ask the user before changing scope, core architecture, delivery guarantees, major security trade-offs, required technologies, the free/local-first constraint, or major planned features.

If the main session cannot select Sol/high directly, it acts as the environment-provided manager model and records that fact in `docs/PLAN.md`; it must not create an extra manager role merely to imitate the requested model.

## Project invariants

- PostgreSQL is the durable source of truth; Kafka is transport for decoupling, buffering, and worker scaling.
- Delivery semantics are at-least-once. Never claim exactly-once delivery across external HTTP.
- Event, delivery, and outbox intent are persisted atomically.
- Retry scheduling is durable database state; never use `Thread.sleep` or memory-only retry state.
- Requeue paths must avoid PostgreSQL/Kafka dual writes by using the outbox.
- Workers use atomic, concurrency-safe delivery claims and tolerate duplicate Kafka records.
- External HTTP calls do not run while holding a database transaction or row lock. Claim leases/tokens must support crash recovery and stale-worker protection.
- Webhook signatures use versioned HMAC-SHA256 over the raw body and timestamp. Secrets and sensitive payloads are never logged.
- Metrics use bounded-cardinality labels; event IDs, delivery IDs, full URLs, and similar values belong in structured logs, not metric labels.
- Time and randomness that affect retries are injectable/testable.
- Core development, tests, monitoring, and demo flows remain fully local and free. Do not add mandatory paid APIs, SaaS, cloud subscriptions, or proprietary dependencies.
- Keep the React/TypeScript UI deliberately small; it demonstrates real API flows rather than frontend framework complexity.

## Architecture and implementation discipline

- Follow the phases and acceptance criteria in `docs/PLAN.md`; do not replace them with a parallel plan.
- Prefer Java 21, Spring Boot, Maven Wrapper, React/TypeScript, PostgreSQL, Kafka, Flyway, Testcontainers, WireMock, Micrometer, Prometheus, and Grafana as already selected.
- Use versioned Flyway migrations for schema changes. Add database constraints, foreign keys, unique constraints, indexes, atomic transitions, and PostgreSQL locking where the access pattern requires them.
- Keep Kafka messages compact and versioned, normally carrying a delivery reference rather than business payload state.
- Test failure paths and concurrency, not only happy-path CRUD behavior.
- Avoid speculative components: CDC/Debezium, multi-tenancy, complete secret rotation, OpenTelemetry, hosted deployment, and a business-authoritative Kafka DLQ stay deferred until the plan explicitly activates them.

## Git and feature workflow

1. Keep `main` stable and buildable.
2. Use `feature/...`, `fix/...`, `refactor/...`, or `docs/...` branches for meaningful work.
3. Before editing, confirm that the current branch matches the active feature.
4. Split work into meaningful, working increments; the manager creates clear commits and pushes them to `origin`.
5. Do not mix unrelated cleanup into a feature.
6. Before feature completion, the manager reviews implementation, tests, builds, acceptance criteria, `docs/PLAN.md`, and affected documentation.
7. Only the manager merges a completed feature into `main` and verifies `main` afterward.
8. Never use destructive Git commands or discard existing changes without explicit user authorization.

## Verification expectations

Run verification proportional to the change and report exact commands/results. At minimum:

- backend changes: Maven Wrapper tests and package/build as relevant;
- frontend changes: npm tests, lint, and build as relevant;
- database/messaging changes: focused integration tests with real PostgreSQL/Kafka through Testcontainers where applicable;
- container changes: `docker compose config`, build, startup, and health/readiness checks when the Docker daemon is available;
- documentation/configuration changes: inspect rendered/configured behavior and run syntax validation where available;
- before merging a phase: run the full relevant suite and verify every acceptance checkbox with evidence.

Do not mark an environment-dependent criterion complete if it was not actually verified. Record the blocker and the completed substitute checks instead.

## Progress tracking

Update `docs/PLAN.md` whenever work changes the repository's real state:

- mark a task `[~]` when implementation begins, `[x]` only after verification, and `[!]` only for a concrete blocker;
- keep **Current status**, completed work, next work, blockers, and intentionally deferred work accurate;
- update each phase's task list and acceptance criteria from evidence, not intent;
- record important departures in **Plan deviations / decision changes** using `Planned`, `Implemented`, `Reason`, and `Trade-off`;
- use ADRs only for decisions needing more detail; the plan must still contain the current summary;
- update related README/architecture/security/testing/reliability docs in the same feature when behavior or operator instructions change.

At handoff, report the active branch, last relevant commit, checks run, unresolved blocker or next unchecked plan item, and whether changes are pushed.

## Phase boundary handoff

When a phase is complete, stop before starting the next phase. The manager must first:

1. finish review, verification, plan/docs updates, feature push, merge to `main`, and green `main` CI verification;
2. give the user a concise phase report covering implemented behavior, important decisions, tests/acceptance evidence, branch/commit state, and known deferrals;
3. provide exact instructions for seeing and exercising the result locally (browser URLs, representative API commands, and relevant GitHub/CI locations);
4. wait for the user's direction before creating or implementing the next phase.

Do not treat an internally completed coding increment as a completed phase; the stop occurs only after every planned phase task and acceptance criterion has been reviewed and the stable `main` result has been verified.

## Current recovery point

At the latest update, Phases 0–2 are complete and Phase 3 has started on `feature/phase-3-delivery-worker` after explicit user direction. The first increment is the append-only delivery lease schema plus atomic claim, stale-lease recovery, and stale-token tests; HTTP and Kafka consumption follow as separate reviewed increments. Re-read `docs/PLAN.md` and Git history for the exact current branch and commit state.
