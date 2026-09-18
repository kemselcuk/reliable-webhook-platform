CREATE TABLE event_idempotency_keys (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    event_id UUID NOT NULL,
    response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT event_idempotency_keys_event_fk
        FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT event_idempotency_keys_key_unique UNIQUE (idempotency_key),
    CONSTRAINT event_idempotency_keys_key_not_blank CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT event_idempotency_keys_hash_format_check CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_event_idempotency_keys_event_id
    ON event_idempotency_keys (event_id);
