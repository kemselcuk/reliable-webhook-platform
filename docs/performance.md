# Query, locking, and resource review

Phase 9 reviewed the database access paths against PostgreSQL 17 with
`EXPLAIN (ANALYZE, BUFFERS)` on a clean migrated database. The review used
20,000-row list/backlog sets and both sparse and saturated due-work cases; the
data was created inside transactions and rolled back after inspection.

## Read paths

- Endpoint paging uses
  `idx_webhook_endpoints_created_at_id (created_at DESC, id DESC)`. A 20-row
  page used an index-only scan (about 0.03 ms in the local review). V8 replaces
  the earlier creation-time-only index so the database can satisfy the full
  stable sort directly.
- Unfiltered delivery paging uses
  `idx_deliveries_created_at_id`; status-filtered paging can use
  `idx_deliveries_status_created_at_id`. Page size is capped at 100.
- Delivery attempt detail uses the unique `(delivery_id, attempt_number)`
  index and returns immutable attempts in explicit attempt-number order.
- System summary counts are full-table aggregates. They are appropriate for
  this bounded local operations view, but should move to pre-aggregated or
  asynchronously maintained statistics before high-volume production use.

## Work-selection paths and locking

- Due retry selection uses `idx_deliveries_status_retry`. With 100 due rows in
  a 20,000-row set, PostgreSQL selected the index and returned the 50-row batch
  in about 0.2 ms locally.
- Outbox selection combines the pending-availability and stale-claim partial
  indexes with a `BitmapOr` when eligible work is sparse. If nearly every row
  is eligible, PostgreSQL correctly prefers a sequential scan; the batch is
  still capped, and a 20,000-row saturated local review completed in under
  10 ms.
- Retry and outbox polling use `FOR UPDATE SKIP LOCKED`, so concurrent pollers
  do not wait on work already selected by another poller. Delivery processing
  uses short token-guarded claim/completion transactions, and external HTTP is
  performed only after the claim transaction has committed.
- Idempotent event creation uses a transaction-scoped advisory lock derived
  from the key. It serializes only requests sharing the same 64-bit lock value;
  the unique database constraint remains the final integrity guard.

The polling publisher and retry scheduler default to batches of 50 and both
reject values above 500. Worker concurrency defaults to three and is capped at
100. These application bounds prevent a configuration typo from creating an
unbounded lock or request burst.

## Local containers and health

Compose assigns explicit CPU, memory, and PID limits to every service. During
the Phase 9 clean-stack demo, Kafka remained below its 1 GiB limit, the backend
below its 768 MiB limit, and all other services below their smaller limits.
These are development containment values, not production sizing guidance.
The JDBC pool is also explicit: at most 10 connections, two idle connections,
a three-second acquisition timeout, and a two-second validation timeout.

Backend readiness includes PostgreSQL. Kafka remains outside the readiness
group because broker downtime is a supported degraded mode: the API can commit
event, delivery, and outbox state while publication waits durably. Liveness is
process-local to avoid dependency-driven restart loops. Container dependency
checks are bounded by timeouts and retry counts.
