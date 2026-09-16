import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import {
  ApiError,
  createEndpoint,
  createEvent,
  loadEndpoints,
  loadHealth,
  type HealthResponse,
  type WebhookEndpoint,
} from './api';
import { parseJsonPayload } from './api-helpers.js';
import { healthStatusLabel } from './health-status.js';

type HealthState =
  | { kind: 'loading' }
  | { kind: 'healthy'; data: HealthResponse }
  | { kind: 'error'; message: string };

type EndpointState =
  | { kind: 'loading' }
  | { kind: 'ready'; items: WebhookEndpoint[] }
  | { kind: 'error'; message: string };

type Notice = { kind: 'success' | 'error'; message: string } | null;

function isHealthResponse(value: unknown): value is HealthResponse {
  if (typeof value !== 'object' || value === null) {
    return false;
  }

  const candidate = value as Record<string, unknown>;
  return typeof candidate.status === 'string' && typeof candidate.service === 'string';
}

function errorMessage(error: unknown): string {
  if (error instanceof DOMException && error.name === 'AbortError') {
    return '';
  }
  if (error instanceof ApiError || error instanceof Error) {
    return error.message;
  }
  return 'The request could not be completed. Please try again.';
}

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

export default function App() {
  const [health, setHealth] = useState<HealthState>({ kind: 'loading' });
  const [endpoints, setEndpoints] = useState<EndpointState>({ kind: 'loading' });
  const [endpointForm, setEndpointForm] = useState({ name: '', url: '' });
  const [endpointBusy, setEndpointBusy] = useState(false);
  const [endpointNotice, setEndpointNotice] = useState<Notice>(null);
  const [selectedEndpointIds, setSelectedEndpointIds] = useState<Set<string>>(new Set());
  const [eventType, setEventType] = useState('order.created');
  const [payloadText, setPayloadText] = useState(`{
  "orderId": "demo-123"
}`);
  const [eventBusy, setEventBusy] = useState(false);
  const [eventNotice, setEventNotice] = useState<Notice>(null);

  const refreshEndpoints = useCallback(async (signal?: AbortSignal) => {
    setEndpoints({ kind: 'loading' });
    try {
      const page = await loadEndpoints(0, 100, signal);
      setEndpoints({ kind: 'ready', items: page.items });
      setSelectedEndpointIds((previous) => {
        const enabledIds = new Set(page.items.filter((endpoint) => endpoint.enabled).map((endpoint) => endpoint.id));
        return new Set([...previous].filter((id) => enabledIds.has(id)));
      });
    } catch (error: unknown) {
      const message = errorMessage(error);
      if (message) {
        setEndpoints({ kind: 'error', message });
      }
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();

    void loadHealth(controller.signal)
      .then((data) => {
        if (!isHealthResponse(data)) {
          throw new Error('The backend returned an unexpected health response.');
        }
        setHealth({ kind: 'healthy', data });
      })
      .catch((error: unknown) => {
        const message = errorMessage(error);
        if (message) {
          setHealth({ kind: 'error', message });
        }
      });
    void refreshEndpoints(controller.signal);

    return () => controller.abort();
  }, [refreshEndpoints]);

  const enabledEndpoints = useMemo(
    () => (endpoints.kind === 'ready' ? endpoints.items.filter((endpoint) => endpoint.enabled) : []),
    [endpoints],
  );

  function toggleEndpoint(endpointId: string, checked: boolean) {
    setSelectedEndpointIds((previous) => {
      const next = new Set(previous);
      if (checked) {
        next.add(endpointId);
      } else {
        next.delete(endpointId);
      }
      return next;
    });
  }

  async function handleEndpointSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setEndpointBusy(true);
    setEndpointNotice(null);
    try {
      await createEndpoint(endpointForm.name.trim(), endpointForm.url.trim());
      setEndpointForm({ name: '', url: '' });
      setEndpointNotice({ kind: 'success', message: 'Endpoint created and list refreshed.' });
      void refreshEndpoints();
    } catch (error: unknown) {
      const message = errorMessage(error);
      if (message) {
        setEndpointNotice({ kind: 'error', message });
      }
    } finally {
      setEndpointBusy(false);
    }
  }

  async function handleEventSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const parsedPayload = parseJsonPayload(payloadText);
    if (!eventType.trim()) {
      setEventNotice({ kind: 'error', message: 'Enter an event type.' });
      return;
    }
    if (selectedEndpointIds.size === 0) {
      setEventNotice({ kind: 'error', message: 'Select at least one enabled endpoint.' });
      return;
    }
    if (!parsedPayload.ok) {
      setEventNotice({ kind: 'error', message: parsedPayload.error });
      return;
    }

    setEventBusy(true);
    setEventNotice(null);
    try {
      const response = await createEvent(eventType.trim(), parsedPayload.value, [...selectedEndpointIds]);
      setEventNotice({
        kind: 'success',
        message: `Event ${response.id} created with ${response.deliveryIds.length} delivery ${response.deliveryIds.length === 1 ? 'target' : 'targets'}. Delivery IDs: ${response.deliveryIds.join(', ')}`,
      });
    } catch (error: unknown) {
      const message = errorMessage(error);
      if (message) {
        setEventNotice({ kind: 'error', message });
      }
    } finally {
      setEventBusy(false);
    }
  }

  return (
    <main className="shell">
      <header className="hero">
        <div>
          <p className="eyebrow">Phase 1 workspace</p>
          <h1>Reliable Webhook Platform</h1>
          <p className="intro">
            Register local or remote webhook endpoints, then submit an event to selected targets.
          </p>
        </div>
        <div className={`health-badge ${health.kind}`} role="status" aria-live="polite">
          <span className="status-dot" aria-hidden="true" />
          {health.kind === 'loading' && 'Checking API'}
          {health.kind === 'error' && 'API unavailable'}
          {health.kind === 'healthy' && healthStatusLabel(health.data.status)}
        </div>
      </header>

      {health.kind === 'error' && <p className="inline-error" role="alert">{health.message}</p>}
      {health.kind === 'healthy' && <p className="health-detail">{health.data.service} is responding.</p>}

      <div className="workspace-grid">
        <section className="panel" aria-labelledby="endpoint-heading">
          <div className="section-heading">
            <div>
              <p className="eyebrow">Destinations</p>
              <h2 id="endpoint-heading">Webhook endpoints</h2>
            </div>
            {endpoints.kind === 'ready' && <span className="count-label">{endpoints.items.length} shown</span>}
          </div>

          <form className="form" onSubmit={handleEndpointSubmit}>
            <div className="form-grid">
              <label>
                Name
                <input
                  value={endpointForm.name}
                  onChange={(event) => setEndpointForm({ ...endpointForm, name: event.target.value })}
                  maxLength={255}
                  required
                  autoComplete="off"
                  placeholder="Orders receiver"
                />
              </label>
              <label>
                URL
                <input
                  value={endpointForm.url}
                  onChange={(event) => setEndpointForm({ ...endpointForm, url: event.target.value })}
                  maxLength={2048}
                  required
                  type="url"
                  inputMode="url"
                  autoComplete="url"
                  placeholder="http://localhost:8081/webhooks"
                />
              </label>
            </div>
            <button type="submit" disabled={endpointBusy}>
              {endpointBusy ? 'Creating…' : 'Create endpoint'}
            </button>
          </form>
          {endpointNotice && (
            <p className={`notice ${endpointNotice.kind}`} role={endpointNotice.kind === 'error' ? 'alert' : 'status'}>
              {endpointNotice.message}
            </p>
          )}

          <div className="list-region" aria-live="polite">
            {endpoints.kind === 'loading' && <p className="muted">Loading endpoints…</p>}
            {endpoints.kind === 'error' && (
              <div className="empty-state">
                <p className="inline-error" role="alert">{endpoints.message}</p>
                <button type="button" className="secondary-button" onClick={() => void refreshEndpoints()}>
                  Try again
                </button>
              </div>
            )}
            {endpoints.kind === 'ready' && endpoints.items.length === 0 && (
              <p className="muted">No endpoints yet. Create one above to receive events.</p>
            )}
            {endpoints.kind === 'ready' && endpoints.items.length > 0 && (
              <ul className="endpoint-list">
                {endpoints.items.map((endpoint) => (
                  <li key={endpoint.id} className="endpoint-item">
                    <div className="endpoint-main">
                      <strong>{endpoint.name}</strong>
                      <code>{endpoint.url}</code>
                    </div>
                    <div className="endpoint-meta">
                      <span className={`state-pill ${endpoint.enabled ? 'enabled' : 'disabled'}`}>
                        {endpoint.enabled ? 'Enabled' : 'Disabled'}
                      </span>
                      <time dateTime={endpoint.createdAt}>Created {formatDate(endpoint.createdAt)}</time>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </section>

        <section className="panel" aria-labelledby="event-heading">
          <div className="section-heading">
            <div>
              <p className="eyebrow">Submission</p>
              <h2 id="event-heading">Send an event</h2>
            </div>
          </div>

          <form className="form" onSubmit={handleEventSubmit}>
            <label>
              Event type
              <input
                value={eventType}
                onChange={(event) => setEventType(event.target.value)}
                maxLength={255}
                required
                placeholder="order.created"
              />
            </label>

            <fieldset>
              <legend>Enabled endpoints</legend>
              {endpoints.kind === 'loading' && <p className="muted">Loading endpoint choices…</p>}
              {endpoints.kind === 'error' && <p className="muted">Endpoint choices are unavailable until the list loads.</p>}
              {endpoints.kind === 'ready' && enabledEndpoints.length === 0 && (
                <p className="muted">Create an enabled endpoint before sending an event.</p>
              )}
              {enabledEndpoints.length > 0 && (
                <div className="checkbox-list">
                  {enabledEndpoints.map((endpoint) => (
                    <label key={endpoint.id} className="checkbox-label">
                      <input
                        type="checkbox"
                        checked={selectedEndpointIds.has(endpoint.id)}
                        onChange={(event) => toggleEndpoint(endpoint.id, event.target.checked)}
                      />
                      <span>
                        <strong>{endpoint.name}</strong>
                        <small>{endpoint.url}</small>
                      </span>
                    </label>
                  ))}
                </div>
              )}
            </fieldset>

            <label>
              JSON payload
              <textarea
                value={payloadText}
                onChange={(event) => setPayloadText(event.target.value)}
                rows={8}
                spellCheck={false}
                aria-describedby="payload-help"
              />
              <span id="payload-help" className="field-help">Use a JSON object. It is sent as entered after parsing.</span>
            </label>

            <button type="submit" disabled={eventBusy || endpoints.kind !== 'ready' || enabledEndpoints.length === 0}>
              {eventBusy ? 'Submitting…' : 'Submit event'}
            </button>
          </form>
          {eventNotice && (
            <p className={`notice ${eventNotice.kind}`} role={eventNotice.kind === 'error' ? 'alert' : 'status'}>
              {eventNotice.message}
            </p>
          )}
        </section>
      </div>
    </main>
  );
}
