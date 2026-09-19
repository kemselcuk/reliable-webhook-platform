#!/usr/bin/env python3
"""Dependency-free local receiver that fails once and then succeeds."""

from __future__ import annotations

import json
import sys
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class AttemptState:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._statuses: list[int] = []

    def record(self) -> int:
        with self._lock:
            status = 500 if not self._statuses else 204
            self._statuses.append(status)
            return status

    def reset(self) -> None:
        with self._lock:
            self._statuses.clear()

    def snapshot(self) -> list[int]:
        with self._lock:
            return list(self._statuses)


STATE = AttemptState()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self._respond(200, {"status": "UP"})
            return
        if self.path == "/attempts":
            statuses = STATE.snapshot()
            self._respond(200, {"count": len(statuses), "responseStatuses": statuses})
            return
        self._respond(404, {"error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path == "/reset":
            STATE.reset()
            self._respond(204)
            return
        if self.path == "/webhooks":
            length = int(self.headers.get("Content-Length", "0"))
            if length:
                self.rfile.read(length)
            self._respond(STATE.record())
            return
        self._respond(404, {"error": "not_found"})

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def _respond(self, status: int, body: dict[str, object] | None = None) -> None:
        encoded = b"" if body is None else json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Length", str(len(encoded)))
        if encoded:
            self.send_header("Content-Type", "application/json")
        self.end_headers()
        if encoded:
            self.wfile.write(encoded)


def healthcheck() -> int:
    try:
        with urllib.request.urlopen("http://127.0.0.1:8081/health", timeout=2) as response:
            return 0 if response.status == 200 else 1
    except OSError:
        return 1


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--healthcheck":
        raise SystemExit(healthcheck())
    ThreadingHTTPServer(("0.0.0.0", 8081), Handler).serve_forever()
