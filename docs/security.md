# Webhook signing and secret handling

Phase 6 signs every outbound webhook request with HMAC-SHA256. Endpoint creation accepts a caller-supplied secret between 32 and 512 UTF-8 bytes. The exact secret bytes are stored as recoverable `BYTEA` material in `webhook_endpoint_secrets` because the worker must sign outbound requests. The secret is not part of endpoint response/list DTOs, logs, metrics, Kafka commands, or entity `toString()` output.

Each endpoint starts with active key identifier `v1` and secret version `1`. The separate table and partial unique index allow later rotation to add history while keeping exactly one active secret. Phase 6 intentionally does not add a rotation API or activation workflow.

## Signing contract

The worker serializes the JSON payload once to the UTF-8 bytes sent as the request body. It signs this exact byte sequence with the ASCII Unix-seconds timestamp and a literal period:

```text
signing_input = ASCII(str(timestamp_seconds)) + b"." + raw_body_bytes
signature = HMAC-SHA256(secret_bytes, signing_input)
```

Headers are:

```text
X-Webhook-Timestamp: <timestamp_seconds>
X-Webhook-Signature: v1=<lowercase hexadecimal digest>
X-Webhook-Key-Id: v1
X-Webhook-Id: <event UUID>
X-Delivery-Id: <delivery UUID>
X-Webhook-Event: <event type>
```

The key identifier is bounded metadata for future rotation lookup; it is not secret material.

## Receiver verification

Verify the timestamp and signature before JSON parsing or any body normalization. A five-minute timestamp tolerance is the recommended starting point; receivers should choose a bounded window appropriate to their clock synchronization and retry behavior. Use a constant-time comparison such as Python's `hmac.compare_digest`, and reject malformed versions, non-decimal timestamps, missing headers, and stale/future timestamps.

```python
import hashlib
import hmac
import time


def verify_webhook(secret: bytes, timestamp_header: str,
                  signature_header: str, raw_body: bytes,
                  now: int | None = None, tolerance_seconds: int = 300) -> bool:
    if (not isinstance(timestamp_header, str) or
            not timestamp_header.isascii() or
            not timestamp_header.isdigit()):
        return False
    if (not isinstance(signature_header, str) or
            not isinstance(raw_body, bytes) or
            not signature_header.startswith("v1=")):
        return False
    try:
        timestamp = int(timestamp_header, 10)
    except ValueError:
        return False
    if now is None:
        now = int(time.time())
    if abs(now - timestamp) > tolerance_seconds:
        return False
    signed = timestamp_header.encode("ascii") + b"." + raw_body
    expected = hmac.new(secret, signed, hashlib.sha256).hexdigest()
    supplied = signature_header[3:]
    return hmac.compare_digest(expected, supplied)
```

The receiver should use the `X-Webhook-Key-Id` value to select the appropriate active or recently retired secret during a future rotation transition. Until then, `v1` identifies the endpoint's initial active secret.

## Operational precautions

- Do not log request bodies, secrets, authorization material, or full signature inputs.
- Do not expose endpoint secrets in API DTOs, browser endpoint lists, Kafka messages, metrics labels, exceptions, or debugging `toString()` output.
- Protect PostgreSQL credentials and local volumes because signing material is recoverable by the delivery worker.
- Store the caller's secret in a password manager or receiver configuration and do not commit it to source control.
- Keep receiver clocks synchronized; a five-minute window limits replay exposure but does not replace receiver-side event/delivery idempotency.

## V5 to V6 local upgrade

When Flyway upgrades an existing V5 database, V6 creates one active `v1` secret for every existing endpoint. The backfill uses PostgreSQL `gen_random_bytes(32)`, so these values are arbitrary binary material and must be provisioned as bytes (for example, base64-decoded), not treated as UTF-8 text. The value is intentionally unavailable through the endpoint API, browser list, logs, or Kafka messages.

For a preserved local database, stop delivery workers, the outbox publisher, and the retry scheduler before starting the backend once with `WEBHOOK_DELIVERY_WORKER_ENABLED=false`, `WEBHOOK_OUTBOX_PUBLISHER_ENABLED=false`, and `WEBHOOK_DELIVERY_RETRY_SCHEDULER_ENABLED=false`, so Flyway can apply V6 without sending unprovisioned requests. Retrieve the active material only through privileged local database access, provision each receiver, remove temporary output, and then re-enable delivery. A concise retrieval query is:

```sql
SELECT webhook_endpoint_id, encode(secret_material, 'base64') AS secret_base64
FROM webhook_endpoint_secrets
WHERE active = TRUE
ORDER BY webhook_endpoint_id;
```

Treat the query result as secret material: run it only in a trusted terminal, avoid inline database credentials in shell commands, and clear terminal scrollback/history or other captured output according to local policy. Never paste the result into logs, tickets, CI, or API requests. For a disposable development stack with no data to preserve, recreating the development database volume and then creating endpoints with caller-supplied secrets is safer and simpler; only use volume deletion when the data is intentionally disposable.
