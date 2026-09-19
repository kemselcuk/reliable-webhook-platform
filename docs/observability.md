# Observability

Phase 7 adds local, bounded-cardinality observability to the backend. The
application remains the source of truth; Prometheus and Grafana observe it but
do not participate in event or delivery decisions.

## Endpoints and local services

The backend exposes the following deliberately small Actuator surface:

- `GET /actuator/health` and `GET /actuator/health/{liveness,readiness}`;
- `GET /actuator/info`;
- `GET /actuator/prometheus`.

Health details are not exposed over HTTP. Prometheus is available at
`http://localhost:9090` in Compose and scrapes the backend readiness-aware
service at `/actuator/prometheus`. Grafana is available at
`http://localhost:3001`; its local dashboard is provisioned automatically and
uses the Prometheus datasource. The default local Grafana credentials are
`admin` / `admin-local-only`; set `GRAFANA_ADMIN_USER` and
`GRAFANA_ADMIN_PASSWORD` for a local override. Do not use the defaults outside
an isolated development environment.

Start the monitoring services with the rest of the local stack:

```bash
docker compose up --build -d
docker compose ps
```

The Prometheus and Grafana data directories use named volumes. The dashboard
and datasource definitions are read-only provisioned files under `ops/`, so a
fresh local volume receives the same setup without a hosted service.

## Metrics

The application emits these stable metric families. The only labels are the
finite values shown below plus the global `application` tag:

| Metric | Labels | Meaning |
| --- | --- | --- |
| `webhook_events_accepted_total` | none | Events accepted after the database transaction commits |
| `webhook_delivery_intents_total` | none | Delivery intents committed with accepted events |
| `webhook_delivery_claims_total` | `outcome` | Claim result such as `claimed`, `busy`, or `terminal` |
| `webhook_delivery_outcomes_total` | `outcome` | Worker result such as `success`, `failed`, or `stale_completion` |
| `webhook_delivery_retries_total` | `outcome` | Durable `retry_scheduled` or `dead` transitions |
| `webhook_delivery_dead_total` | none | Successful transitions into `DEAD` |
| `webhook_delivery_http_duration_seconds` | `result`, `status_class` | Outbound HTTP/transport duration; status classes are `2xx`, `4xx`, `5xx`, `other`, or `transport` |
| `webhook_outbox_publishes_total` | `result` | `published`, `retry`, or `other` outbox outcomes |
| `webhook_kafka_commands_total` | `result` | `processed`, `busy`, `discarded`, or `other` commands |
| `webhook_outbox_backlog` | none | Current pending outbox rows |
| `webhook_delivery_retry_backlog` | none | Current `RETRY_SCHEDULED` rows |
| `webhook_kafka_consumer_lag` | none | Maximum `records-lag-max` across active delivery containers |

Event IDs, delivery IDs, URLs, endpoint names, exception text, Kafka offsets,
and payload values are never metric labels. The gauges query PostgreSQL or
aggregate Kafka client metrics and return an unavailable value rather than
turning a scrape into an application failure when the backing source cannot be
read.

## Structured logs and redaction

Spring Boot's ECS structured console format is enabled for local logs. Event
and delivery paths add only `event_id` and/or `delivery_id` to the scoped MDC.
Messages contain bounded enum outcomes, HTTP status results, and counts; they
do not include request payloads, endpoint URLs, signing secrets, response
bodies, raw exception messages, or authentication material. Log aggregation
should still treat application logs as sensitive operational data and apply
normal access controls.

## OpenTelemetry decision

OpenTelemetry was explicitly reassessed for this phase and remains deferred.
The current local deployment has one backend boundary, and the durable event
ID/delivery ID correlation plus bounded metrics and ECS logs answer the current
diagnostic needs. Introducing trace context propagation across Kafka and the
external receiver would add configuration and lifecycle surface without a
demonstrated cross-service consumer in this phase. Reassess if additional
backend services, independently operated workers, or a receiver-side trace
workflow is introduced; that decision should include propagation, sampling,
and local collector requirements.
