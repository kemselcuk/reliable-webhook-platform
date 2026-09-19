-- Endpoint pages use a stable creation-time/id order. Match the complete sort
-- so the bounded list does not need an incremental sort as the table grows.
CREATE INDEX idx_webhook_endpoints_created_at_id
    ON webhook_endpoints (created_at DESC, id DESC);

DROP INDEX idx_webhook_endpoints_created_at;
