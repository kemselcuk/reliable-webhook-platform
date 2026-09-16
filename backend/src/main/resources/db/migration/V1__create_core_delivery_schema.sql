CREATE TABLE webhook_endpoints (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT webhook_endpoints_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT webhook_endpoints_url_not_blank CHECK (length(btrim(url)) > 0),
    CONSTRAINT webhook_endpoints_name_unique UNIQUE (name)
);

CREATE TABLE events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT events_type_not_blank CHECK (length(btrim(event_type)) > 0)
);

CREATE TABLE deliveries (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    webhook_endpoint_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT deliveries_event_fk
        FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT deliveries_endpoint_fk
        FOREIGN KEY (webhook_endpoint_id) REFERENCES webhook_endpoints (id),
    CONSTRAINT deliveries_status_check CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRY_SCHEDULED', 'SUCCESS', 'FAILED', 'DEAD')
    ),
    CONSTRAINT deliveries_attempt_count_check CHECK (attempt_count >= 0),
    CONSTRAINT deliveries_event_endpoint_unique UNIQUE (event_id, webhook_endpoint_id)
);

CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    http_status INTEGER,
    error_code VARCHAR(128),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT delivery_attempts_delivery_fk
        FOREIGN KEY (delivery_id) REFERENCES deliveries (id),
    CONSTRAINT delivery_attempts_number_check CHECK (attempt_number > 0),
    CONSTRAINT delivery_attempts_outcome_check CHECK (
        outcome IN ('SUCCESS', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE')
    ),
    CONSTRAINT delivery_attempts_http_status_check CHECK (
        http_status IS NULL OR (http_status BETWEEN 100 AND 599)
    ),
    CONSTRAINT delivery_attempts_timestamps_check CHECK (completed_at >= started_at),
    CONSTRAINT delivery_attempts_delivery_number_unique UNIQUE (delivery_id, attempt_number)
);

CREATE INDEX idx_webhook_endpoints_created_at
    ON webhook_endpoints (created_at DESC);

CREATE INDEX idx_events_created_at
    ON events (created_at);

CREATE INDEX idx_deliveries_status_retry
    ON deliveries (status, next_retry_at);

CREATE INDEX idx_deliveries_endpoint_created_at
    ON deliveries (webhook_endpoint_id, created_at DESC);
