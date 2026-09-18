CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE webhook_endpoint_secrets (
    id UUID PRIMARY KEY,
    webhook_endpoint_id UUID NOT NULL,
    secret_version INTEGER NOT NULL,
    key_id VARCHAR(64) NOT NULL,
    secret_material BYTEA NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT webhook_endpoint_secrets_endpoint_fk
        FOREIGN KEY (webhook_endpoint_id) REFERENCES webhook_endpoints (id) ON DELETE CASCADE,
    CONSTRAINT webhook_endpoint_secrets_version_check
        CHECK (secret_version > 0),
    CONSTRAINT webhook_endpoint_secrets_key_id_check
        CHECK (length(btrim(key_id)) BETWEEN 1 AND 64),
    CONSTRAINT webhook_endpoint_secrets_material_length_check
        CHECK (octet_length(secret_material) BETWEEN 32 AND 512),
    CONSTRAINT webhook_endpoint_secrets_endpoint_version_unique
        UNIQUE (webhook_endpoint_id, secret_version),
    CONSTRAINT webhook_endpoint_secrets_endpoint_key_id_unique
        UNIQUE (webhook_endpoint_id, key_id)
);

CREATE UNIQUE INDEX idx_webhook_endpoint_secrets_one_active
    ON webhook_endpoint_secrets (webhook_endpoint_id)
    WHERE active;

INSERT INTO webhook_endpoint_secrets (
    id, webhook_endpoint_id, secret_version, key_id, secret_material, active
)
SELECT gen_random_uuid(), id, 1, 'v1', gen_random_bytes(32), TRUE
FROM webhook_endpoints;
