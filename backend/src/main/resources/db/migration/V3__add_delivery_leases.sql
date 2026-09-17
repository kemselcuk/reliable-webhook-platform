ALTER TABLE deliveries
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_at TIMESTAMPTZ;

ALTER TABLE deliveries
    ADD CONSTRAINT deliveries_claim_state_check CHECK (
        (status = 'PROCESSING' AND claim_token IS NOT NULL AND claimed_at IS NOT NULL)
        OR (status <> 'PROCESSING' AND claim_token IS NULL AND claimed_at IS NULL)
    );

CREATE INDEX idx_deliveries_processing_claimed_at
    ON deliveries (claimed_at, id)
    WHERE status = 'PROCESSING';
