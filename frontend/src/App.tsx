import { useEffect, useState } from 'react';
import { healthStatusLabel } from './health-status.js';

type HealthResponse = {
  status: string;
  service: string;
};

type HealthState =
  | { kind: 'loading' }
  | { kind: 'healthy'; data: HealthResponse }
  | { kind: 'error'; message: string };

function isHealthResponse(value: unknown): value is HealthResponse {
  if (typeof value !== 'object' || value === null) {
    return false;
  }

  const candidate = value as Record<string, unknown>;
  return typeof candidate.status === 'string' && typeof candidate.service === 'string';
}

export default function App() {
  const [health, setHealth] = useState<HealthState>({ kind: 'loading' });

  useEffect(() => {
    const controller = new AbortController();

    async function loadHealth() {
      try {
        const response = await fetch('/api/system/health', { signal: controller.signal });
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }

        const body: unknown = await response.json();
        if (!isHealthResponse(body)) {
          throw new Error('Unexpected health response');
        }

        setHealth({ kind: 'healthy', data: body });
      } catch (error: unknown) {
        if (error instanceof DOMException && error.name === 'AbortError') {
          return;
        }

        const message = error instanceof Error ? error.message : 'Could not reach the backend';
        setHealth({ kind: 'error', message });
      }
    }

    void loadHealth();
    return () => controller.abort();
  }, []);

  return (
    <main className="shell">
      <section className="hero">
        <p className="eyebrow">Phase 0 foundation</p>
        <h1>Reliable Webhook Platform</h1>
        <p className="intro">
          A local-first workspace for durable, observable webhook delivery.
        </p>
      </section>

      <section className="status-card" aria-live="polite">
        <div className="status-heading">
          <div>
            <p className="eyebrow">System check</p>
            <h2>Backend health</h2>
          </div>
          <span className={`status-dot ${health.kind}`} aria-hidden="true" />
        </div>

        {health.kind === 'loading' && <p className="status-message">Checking the API…</p>}
        {health.kind === 'error' && (
          <p className="status-message error">Backend unavailable: {health.message}</p>
        )}
        {health.kind === 'healthy' && (
          <div className="status-message healthy">
            <strong>{healthStatusLabel(health.data.status)}</strong>
            <span>{health.data.service}</span>
          </div>
        )}
      </section>
    </main>
  );
}
