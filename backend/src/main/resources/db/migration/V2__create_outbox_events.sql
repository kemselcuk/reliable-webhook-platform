CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token UUID,
    claimed_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(2048),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT outbox_events_delivery_fk
        FOREIGN KEY (delivery_id) REFERENCES deliveries (id),
    CONSTRAINT outbox_events_type_not_blank CHECK (length(btrim(event_type)) > 0),
    CONSTRAINT outbox_events_status_check CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED')),
    CONSTRAINT outbox_events_publish_attempts_check CHECK (publish_attempts >= 0),
    CONSTRAINT outbox_events_claim_state_check CHECK (
        (status = 'PENDING' AND claim_token IS NULL AND claimed_at IS NULL AND published_at IS NULL)
        OR (status = 'CLAIMED' AND claim_token IS NOT NULL AND claimed_at IS NOT NULL AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND claim_token IS NULL AND claimed_at IS NULL AND published_at IS NOT NULL)
    )
);

CREATE INDEX idx_outbox_events_pending_available_at
    ON outbox_events (available_at, created_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_events_claimed_at
    ON outbox_events (claimed_at, id)
    WHERE status = 'CLAIMED';
