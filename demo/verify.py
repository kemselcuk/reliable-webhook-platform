#!/usr/bin/env python3
"""Run the clean local failure -> retry -> success acceptance demo."""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
import uuid


PLATFORM = os.getenv("PLATFORM_BASE_URL", "http://backend:8080")
RECEIVER = os.getenv("DEMO_RECEIVER_URL", "http://127.0.0.1:8081")


def request(method: str, url: str, body: dict[str, object] | None = None) -> tuple[int, bytes]:
    data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    headers = {} if data is None else {"Content-Type": "application/json"}
    call = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(call, timeout=5) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{method} {url} returned {error.code}: {error.read().decode()}") from error


def json_request(method: str, url: str, body: dict[str, object] | None = None) -> dict[str, object]:
    status, raw = request(method, url, body)
    if status < 200 or status >= 300:
        raise RuntimeError(f"{method} {url} returned {status}")
    return {} if not raw else json.loads(raw)


def metrics() -> str:
    _, raw = request("GET", f"{PLATFORM}/actuator/prometheus")
    return raw.decode()


def sample(exposition: str, name: str, labels: dict[str, str] | None = None) -> float:
    required = [] if labels is None else [f'{key}="{value}"' for key, value in labels.items()]
    for line in exposition.splitlines():
        if line.startswith("#") or not (line.startswith(name + "{") or line.startswith(name + " ")):
            continue
        if all(label in line for label in required):
            return float(line.rsplit(" ", 1)[1])
    return 0.0


METRICS = {
    "eventsAccepted": ("webhook_events_accepted_total", None),
    "deliveryIntents": ("webhook_delivery_intents_total", None),
    "retryScheduled": ("webhook_delivery_retries_total", {"outcome": "retry_scheduled"}),
    "failedOutcomes": ("webhook_delivery_outcomes_total", {"outcome": "failed"}),
    "successfulOutcomes": ("webhook_delivery_outcomes_total", {"outcome": "success"}),
    "http5xx": (
        "webhook_delivery_http_duration_seconds_count",
        {"result": "http", "status_class": "5xx"},
    ),
    "http2xx": (
        "webhook_delivery_http_duration_seconds_count",
        {"result": "http", "status_class": "2xx"},
    ),
}


def snapshot() -> dict[str, float]:
    exposition = metrics()
    return {key: sample(exposition, name, labels) for key, (name, labels) in METRICS.items()}


def main() -> None:
    request("POST", f"{RECEIVER}/reset")
    before = snapshot()
    suffix = uuid.uuid4().hex[:12]
    endpoint = json_request(
        "POST",
        f"{PLATFORM}/api/webhook-endpoints",
        {
            "name": f"Retry demo {suffix}",
            "url": "http://demo-receiver:8081/webhooks",
            "secret": "phase-9-local-demo-secret-32-bytes",
        },
    )
    event = json_request(
        "POST",
        f"{PLATFORM}/api/events",
        {
            "type": "order.created",
            "payload": {"orderId": f"demo-{suffix}"},
            "endpointIds": [endpoint["id"]],
        },
    )
    delivery_id = event["deliveryIds"][0]

    deadline = time.monotonic() + 30
    detail: dict[str, object] = {}
    while time.monotonic() < deadline:
        detail = json_request("GET", f"{PLATFORM}/api/deliveries/{delivery_id}")
        delivery = detail["delivery"]
        if delivery["status"] == "SUCCESS" and len(detail["attempts"]) == 2:
            break
        time.sleep(0.2)
    else:
        raise RuntimeError(f"delivery did not succeed: {json.dumps(detail)}")

    receiver = json_request("GET", f"{RECEIVER}/attempts")
    expected_deltas = {
        "eventsAccepted": 1.0,
        "deliveryIntents": 1.0,
        "retryScheduled": 1.0,
        "failedOutcomes": 1.0,
        "successfulOutcomes": 1.0,
        "http5xx": 1.0,
        "http2xx": 1.0,
    }
    while time.monotonic() < deadline:
        after = snapshot()
        deltas = {key: after[key] - before[key] for key in METRICS}
        if deltas == expected_deltas:
            break
        time.sleep(0.1)
    else:
        raise RuntimeError(f"unexpected metric deltas: {json.dumps(deltas, sort_keys=True)}")

    attempts = detail["attempts"]
    outcomes = [attempt["outcome"] for attempt in attempts]
    statuses = [attempt["httpStatus"] for attempt in attempts]
    if outcomes != ["RETRYABLE_FAILURE", "SUCCESS"] or statuses != [500, 204]:
        raise RuntimeError(f"unexpected attempt history: {json.dumps(attempts)}")
    if receiver != {"count": 2, "responseStatuses": [500, 204]}:
        raise RuntimeError(f"unexpected receiver state: {json.dumps(receiver)}")

    print(json.dumps({
        "endpointId": endpoint["id"],
        "eventId": event["id"],
        "deliveryId": delivery_id,
        "deliveryStatus": detail["delivery"]["status"],
        "attemptOutcomes": outcomes,
        "attemptHttpStatuses": statuses,
        "receiver": receiver,
        "metricDeltas": deltas,
    }, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
