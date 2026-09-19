-- Delivery browser reads are ordered by the durable creation timestamp and id.
-- Keep the existing status/retry index for worker scheduling; these indexes
-- cover the bounded list API without scanning all deliveries.
CREATE INDEX idx_deliveries_created_at_id
    ON deliveries (created_at DESC, id DESC);

CREATE INDEX idx_deliveries_status_created_at_id
    ON deliveries (status, created_at DESC, id DESC);
