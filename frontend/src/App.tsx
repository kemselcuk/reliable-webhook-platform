import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ApiError,
  createEndpoint,
  createEvent,
  loadDelivery,
  loadDeliveries,
  loadEndpoints,
  loadHealth,
  loadSystemSummary,
  replayDelivery,
  setEndpointEnabled,
  type DeliveryDetail,
  type DeliveryPage,
  type DeliveryStatus,
  type HealthResponse,
  type SystemSummary,
  type WebhookEndpoint,
} from './api';
import { parseJsonPayload, signingSecretValidationMessage } from './api-helpers.js';
import { deliveryStatusLabel, pluralizeAttempts } from './delivery-formatters.js';
import { healthStatusLabel } from './health-status.js';

type HealthState =
  | { kind: 'loading' }
  | { kind: 'healthy'; data: HealthResponse }
  | { kind: 'error'; message: string };
type EndpointState =
  | { kind: 'loading' }
  | { kind: 'ready'; items: WebhookEndpoint[] }
  | { kind: 'error'; message: string };
type SummaryState =
  | { kind: 'loading' }
  | { kind: 'ready'; data: SystemSummary }
  | { kind: 'error'; message: string };
type DeliveryListState =
  | { kind: 'loading' }
  | { kind: 'ready'; data: DeliveryPage }
  | { kind: 'error'; message: string };
type DeliveryDetailState =
  | { kind: 'idle' | 'loading' }
  | { kind: 'ready'; data: DeliveryDetail }
  | { kind: 'error'; message: string };
type Notice = { kind: 'success' | 'error'; message: string } | null;

function isHealthResponse(value: unknown): value is HealthResponse {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.status === 'string' && typeof candidate.service === 'string';
}

function errorMessage(error: unknown): string {
  if (error instanceof DOMException && error.name === 'AbortError') return '';
  if (error instanceof ApiError || error instanceof Error) return error.message;
  return 'The request could not be completed. Please try again.';
}

function formatDate(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

const deliveryStatuses: DeliveryStatus[] = [
  'PENDING', 'PROCESSING', 'RETRY_SCHEDULED', 'SUCCESS', 'FAILED', 'DEAD',
];

export default function App() {
  const [health, setHealth] = useState<HealthState>({ kind: 'loading' });
  const [summary, setSummary] = useState<SummaryState>({ kind: 'loading' });
  const [endpoints, setEndpoints] = useState<EndpointState>({ kind: 'loading' });
  const [endpointForm, setEndpointForm] = useState({ name: '', url: '', secret: '' });
  const [endpointBusy, setEndpointBusy] = useState(false);
  const [endpointBusyId, setEndpointBusyId] = useState<string | null>(null);
  const [endpointNotice, setEndpointNotice] = useState<Notice>(null);
  const [selectedEndpointIds, setSelectedEndpointIds] = useState<Set<string>>(new Set());
  const [eventType, setEventType] = useState('order.created');
  const [payloadText, setPayloadText] = useState(`{
  "orderId": "demo-123"
}`);
  const [idempotencyKey, setIdempotencyKey] = useState('');
  const [eventBusy, setEventBusy] = useState(false);
  const [eventNotice, setEventNotice] = useState<Notice>(null);
  const [lastCreatedDeliveryId, setLastCreatedDeliveryId] = useState<string | null>(null);
  const [deliveryStatusFilter, setDeliveryStatusFilter] = useState<DeliveryStatus | ''>('');
  const [deliveryPageNumber, setDeliveryPageNumber] = useState(0);
  const [deliveryList, setDeliveryList] = useState<DeliveryListState>({ kind: 'loading' });
  const [deliveryDetail, setDeliveryDetail] = useState<DeliveryDetailState>({ kind: 'idle' });
  const [selectedDeliveryId, setSelectedDeliveryId] = useState<string | null>(null);
  const [replayBusy, setReplayBusy] = useState(false);
  const [detailNotice, setDetailNotice] = useState<Notice>(null);
  const deliveryRequestSequence = useRef(0);

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
      if (message) setEndpoints({ kind: 'error', message });
    }
  }, []);

  const refreshSummary = useCallback(async (signal?: AbortSignal) => {
    setSummary({ kind: 'loading' });
    try {
      setSummary({ kind: 'ready', data: await loadSystemSummary(signal) });
    } catch (error: unknown) {
      const message = errorMessage(error);
      if (message) setSummary({ kind: 'error', message });
    }
  }, []);

  const refreshDeliveries = useCallback(async (signal?: AbortSignal) => {
    setDeliveryList({ kind: 'loading' });
    try {
      const data = await loadDeliveries(deliveryPageNumber, 20, deliveryStatusFilter || undefined, signal);
      setDeliveryList({ kind: 'ready', data });
    } catch (error: unknown) {
      const message = errorMessage(error);
      if (message) setDeliveryList({ kind: 'error', message });
    }
  }, [deliveryPageNumber, deliveryStatusFilter]);

  const selectDelivery = useCallback(async (deliveryId: string) => {
    const requestSequence = deliveryRequestSequence.current + 1;
    deliveryRequestSequence.current = requestSequence;
    setSelectedDeliveryId(deliveryId);
    setDetailNotice(null);
    setDeliveryDetail({ kind: 'loading' });
    try {
      const data = await loadDelivery(deliveryId);
      if (requestSequence === deliveryRequestSequence.current) {
        setDeliveryDetail({ kind: 'ready', data });
      }
    } catch (error: unknown) {
      const message = errorMessage(error);
      if (message && requestSequence === deliveryRequestSequence.current) setDeliveryDetail({ kind: 'error', message });
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void loadHealth(controller.signal).then((data) => {
      if (!isHealthResponse(data)) throw new Error('The backend returned an unexpected health response.');
      setHealth({ kind: 'healthy', data });
    }).catch((error: unknown) => {
      const message = errorMessage(error);
      if (message) setHealth({ kind: 'error', message });
    });
    void refreshEndpoints(controller.signal);
    void refreshSummary(controller.signal);
    return () => controller.abort();
  }, [refreshEndpoints, refreshSummary]);

  useEffect(() => {
    const controller = new AbortController();
    void refreshDeliveries(controller.signal);
    return () => controller.abort();
  }, [refreshDeliveries]);

  const enabledEndpoints = useMemo(
    () => (endpoints.kind === 'ready' ? endpoints.items.filter((endpoint) => endpoint.enabled) : []),
    [endpoints],
  );

  function toggleEndpointSelection(endpointId: string, checked: boolean) {
    setSelectedEndpointIds((previous) => {
      const next = new Set(previous);
      if (checked) next.add(endpointId); else next.delete(endpointId);
      return next;
    });
  }

  async function handleEndpointSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const secretValidationMessage = signingSecretValidationMessage(endpointForm.secret);
    if (secretValidationMessage) {
      setEndpointNotice({ kind: 'error', message: secretValidationMessage });
      return;
    }
    setEndpointBusy(true);
    setEndpointNotice(null);
    try {
      await createEndpoint(endpointForm.name.trim(), endpointForm.url.trim(), endpointForm.secret);
      setEndpointForm({ name: '', url: '', secret: '' });
      setEndpointNotice({ kind: 'success', message: 'Endpoint created and list refreshed.' });
      void refreshEndpoints();
      void refreshSummary();
    } catch (error: unknown) {
      const message = errorMessage(error);
      if (message) setEndpointNotice({ kind: 'error', message });
    } finally {
      setEndpointBusy(false);
    }
  }

  async function handleEndpointEnabled(endpoint: WebhookEndpoint) {
    setEndpointBusyId(endpoint.id);
    setEndpointNotice(null);
    try {
      await setEndpointEnabled(endpoint.id, !endpoint.enabled);
      await refreshEndpoints();
      setEndpointNotice({ kind: 'success', message: `${endpoint.name} is now ${endpoint.enabled ? 'disabled' : 'enabled'}.` });
    } catch (error: unknown) {
      const message = errorMessage(error);
      if (message) setEndpointNotice({ kind: 'error', message });
    } finally {
      setEndpointBusyId(null);
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
      const response = await createEvent(eventType.trim(), parsedPayload.value, [...selectedEndpointIds], idempotencyKey);
      const firstDeliveryId = response.deliveryIds[0] ?? null;
      setIdempotencyKey('');
      setLastCreatedDeliveryId(firstDeliveryId);
      setEventNotice({
        kind: 'success',
        message: `Event accepted with ${response.deliveryIds.length} delivery ${response.deliveryIds.length === 1 ? 'target' : 'targets'}. The first delivery is selected below.`,
      });
      void refreshDeliveries();
      void refreshSummary();
      if (firstDeliveryId) void selectDelivery(firstDeliveryId);
    } catch (error: unknown) {
      const message = errorMessage(error);
      if (message) setEventNotice({ kind: 'error', message });
    } finally {
      setEventBusy(false);
    }
  }

  async function handleReplay() {
    if (!selectedDeliveryId || deliveryDetail.kind !== 'ready' || !deliveryDetail.data.delivery.replayable) return;
    setReplayBusy(true);
    setDetailNotice(null);
    try {
      await replayDelivery(selectedDeliveryId);
      await Promise.all([refreshDeliveries(), refreshSummary()]);
      await selectDelivery(selectedDeliveryId);
      setDetailNotice({ kind: 'success', message: 'Replay queued. Delivery state will refresh from the database.' });
    } catch (error: unknown) {
      const message = errorMessage(error);
      if (message) setDetailNotice({ kind: 'error', message });
    } finally {
      setReplayBusy(false);
    }
  }

  const selectedDelivery = deliveryDetail.kind === 'ready' ? deliveryDetail.data.delivery : null;

  return (
    <main className="shell">
      <header className="hero">
        <div>
          <p className="eyebrow">Operations workspace</p>
          <h1>Reliable Webhook Platform</h1>
          <p className="intro">Register endpoints, submit events, and inspect durable delivery state from one small local console.</p>
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
          <div className="section-heading"><div><p className="eyebrow">Destinations</p><h2 id="endpoint-heading">Webhook endpoints</h2></div>{endpoints.kind === 'ready' && <span className="count-label">{endpoints.items.length} shown</span>}</div>
          <form className="form" onSubmit={handleEndpointSubmit}>
            <div className="form-grid">
              <label>Name<input value={endpointForm.name} onChange={(event) => setEndpointForm({ ...endpointForm, name: event.target.value })} maxLength={255} required autoComplete="off" placeholder="Orders receiver" /></label>
              <label>URL<input value={endpointForm.url} onChange={(event) => setEndpointForm({ ...endpointForm, url: event.target.value })} maxLength={2048} required type="url" inputMode="url" autoComplete="url" placeholder="http://localhost:8081/webhooks" /></label>
              <label>Signing secret<input value={endpointForm.secret} onChange={(event) => setEndpointForm({ ...endpointForm, secret: event.target.value })} maxLength={512} required type="password" autoComplete="new-password" placeholder="32–512 UTF-8 bytes" /><span className="field-help">Keep this value safe; it is never shown again.</span></label>
            </div>
            <button type="submit" disabled={endpointBusy}>{endpointBusy ? 'Creating…' : 'Create endpoint'}</button>
          </form>
          {endpointNotice && <p className={`notice ${endpointNotice.kind}`} role={endpointNotice.kind === 'error' ? 'alert' : 'status'}>{endpointNotice.message}</p>}
          <div className="list-region" aria-live="polite">
            {endpoints.kind === 'loading' && <p className="muted">Loading endpoints…</p>}
            {endpoints.kind === 'error' && <div className="empty-state"><p className="inline-error" role="alert">{endpoints.message}</p><button type="button" className="secondary-button" onClick={() => void refreshEndpoints()}>Try again</button></div>}
            {endpoints.kind === 'ready' && endpoints.items.length === 0 && <p className="muted">No endpoints yet. Create one above to receive events.</p>}
            {endpoints.kind === 'ready' && endpoints.items.length > 0 && <ul className="endpoint-list">{endpoints.items.map((endpoint) => <li key={endpoint.id} className="endpoint-item">
              <div className="endpoint-main"><strong>{endpoint.name}</strong><code>{endpoint.url}</code></div>
              <div className="endpoint-meta"><span className={`state-pill ${endpoint.enabled ? 'enabled' : 'disabled'}`}>{endpoint.enabled ? 'Enabled' : 'Disabled'}</span><time dateTime={endpoint.createdAt}>Created {formatDate(endpoint.createdAt)}</time><button type="button" className="text-button" onClick={() => void handleEndpointEnabled(endpoint)} disabled={endpointBusyId === endpoint.id} aria-label={`${endpoint.enabled ? 'Disable' : 'Enable'} ${endpoint.name}`}>{endpointBusyId === endpoint.id ? 'Saving…' : endpoint.enabled ? 'Disable' : 'Enable'}</button></div>
            </li>)}</ul>}
          </div>
        </section>

        <section className="panel" aria-labelledby="event-heading">
          <div className="section-heading"><div><p className="eyebrow">Submission</p><h2 id="event-heading">Send an event</h2></div></div>
          <form className="form" onSubmit={handleEventSubmit}>
            <label>Event type<input value={eventType} onChange={(event) => setEventType(event.target.value)} maxLength={255} required placeholder="order.created" /></label>
            <fieldset><legend>Enabled endpoints</legend>
              {endpoints.kind === 'loading' && <p className="muted">Loading endpoint choices…</p>}
              {endpoints.kind === 'error' && <p className="muted">Endpoint choices are unavailable until the list loads.</p>}
              {endpoints.kind === 'ready' && enabledEndpoints.length === 0 && <p className="muted">Create an enabled endpoint before sending an event.</p>}
              {enabledEndpoints.length > 0 && <div className="checkbox-list">{enabledEndpoints.map((endpoint) => <label key={endpoint.id} className="checkbox-label"><input type="checkbox" checked={selectedEndpointIds.has(endpoint.id)} onChange={(event) => toggleEndpointSelection(endpoint.id, event.target.checked)} /><span><strong>{endpoint.name}</strong><small>{endpoint.url}</small></span></label>)}</div>}
            </fieldset>
            <label>Idempotency key (optional)<input value={idempotencyKey} onChange={(event) => setIdempotencyKey(event.target.value)} maxLength={255} autoComplete="off" placeholder="order-submit-123" aria-describedby="idempotency-help" /><span id="idempotency-help" className="field-help">Reuse a key to safely receive the original result.</span></label>
            <label>JSON payload<textarea value={payloadText} onChange={(event) => setPayloadText(event.target.value)} rows={8} spellCheck={false} aria-describedby="payload-help" /><span id="payload-help" className="field-help">Use a JSON object. It is sent after local validation.</span></label>
            <button type="submit" disabled={eventBusy || endpoints.kind !== 'ready' || enabledEndpoints.length === 0}>{eventBusy ? 'Submitting…' : 'Submit event'}</button>
          </form>
          {eventNotice && <div className={`notice ${eventNotice.kind}`} role={eventNotice.kind === 'error' ? 'alert' : 'status'}><span>{eventNotice.message}</span>{eventNotice.kind === 'success' && lastCreatedDeliveryId && <button type="button" className="link-button" onClick={() => void selectDelivery(lastCreatedDeliveryId)}>View first delivery</button>}</div>}
        </section>

        <section className="panel workspace-wide" aria-labelledby="summary-heading">
          <div className="section-heading"><div><p className="eyebrow">Durable state</p><h2 id="summary-heading">System summary</h2></div><button type="button" className="secondary-button" onClick={() => void refreshSummary()}>Refresh</button></div>
          {summary.kind === 'loading' && <p className="muted">Loading system summary…</p>}
          {summary.kind === 'error' && <p className="inline-error" role="alert">{summary.message}</p>}
          {summary.kind === 'ready' && <div className="summary-grid">
            <div><span className="summary-value">{summary.data.endpointCount}</span><span className="summary-label">Endpoints</span></div><div><span className="summary-value">{summary.data.eventCount}</span><span className="summary-label">Events</span></div><div><span className="summary-value">{summary.data.pendingOutbox}</span><span className="summary-label">Outbox backlog</span></div><div><span className="summary-value">{summary.data.retryBacklog}</span><span className="summary-label">Retry backlog</span></div><div><span className="summary-value">{summary.data.acceptedEvents}</span><span className="summary-label">Accepted events (process lifetime)</span></div><div><span className="summary-value">{summary.data.deliveryIntents}</span><span className="summary-label">Delivery intents (process lifetime)</span></div>
            <div className="summary-statuses">{deliveryStatuses.map((status) => <span key={status} className="summary-status">{deliveryStatusLabel(status)} <strong>{summary.data.deliveriesByStatus[status] ?? 0}</strong></span>)}</div>
          </div>}
          <p className="field-help summary-help">Metrics and dashboards remain available through the local Compose monitoring services configured for this deployment.</p>
        </section>

        <section className="panel workspace-wide" aria-labelledby="delivery-heading">
          <div className="section-heading"><div><p className="eyebrow">At-least-once delivery</p><h2 id="delivery-heading">Delivery browser</h2></div><div className="delivery-actions"><label className="compact-label">Status<select value={deliveryStatusFilter} onChange={(event) => { setDeliveryStatusFilter(event.target.value as DeliveryStatus | ''); setDeliveryPageNumber(0); }}><option value="">All</option>{deliveryStatuses.map((status) => <option key={status} value={status}>{deliveryStatusLabel(status)}</option>)}</select></label><button type="button" className="secondary-button" onClick={() => void refreshDeliveries()}>Refresh</button></div></div>
          {deliveryList.kind === 'loading' && <p className="muted">Loading deliveries…</p>}
          {deliveryList.kind === 'error' && <div className="empty-state"><p className="inline-error" role="alert">{deliveryList.message}</p><button type="button" className="secondary-button" onClick={() => void refreshDeliveries()}>Try again</button></div>}
          {deliveryList.kind === 'ready' && deliveryList.data.items.length === 0 && <p className="muted">No deliveries match this filter yet.</p>}
          {deliveryList.kind === 'ready' && deliveryList.data.items.length > 0 && <div className="delivery-browser">
            <div className="delivery-list" role="list" aria-label="Deliveries">{deliveryList.data.items.map((item) => <div role="listitem" key={item.id}><button type="button" className={`delivery-item ${selectedDeliveryId === item.id ? 'selected' : ''}`} onClick={() => void selectDelivery(item.id)}><span className="delivery-item-heading"><strong>{item.endpointName}</strong><span className={`state-pill delivery-${item.status.toLowerCase()}`}>{deliveryStatusLabel(item.status)}</span></span><span className="delivery-item-meta">{item.eventType} · {pluralizeAttempts(item.attemptCount)}</span><code>{item.id}</code></button></div>)}</div>
            {selectedDeliveryId && <div className="delivery-detail" aria-live="polite">
              {deliveryDetail.kind === 'loading' && <p className="muted">Loading delivery detail…</p>}
              {deliveryDetail.kind === 'error' && <p className="inline-error" role="alert">{deliveryDetail.message}</p>}
              {deliveryDetail.kind === 'ready' && selectedDelivery && <><div className="detail-heading"><div><p className="eyebrow">Selected delivery</p><h3>{selectedDelivery.endpointName}</h3></div>{selectedDelivery.replayable && <button type="button" onClick={() => void handleReplay()} disabled={replayBusy}>{replayBusy ? 'Queueing…' : 'Replay delivery'}</button>}</div>
                {detailNotice && <p className={`notice ${detailNotice.kind}`} role={detailNotice.kind === 'error' ? 'alert' : 'status'}>{detailNotice.message}</p>}
                <dl className="detail-grid"><div><dt>Status</dt><dd>{deliveryStatusLabel(selectedDelivery.status)}</dd></div><div><dt>Event</dt><dd>{selectedDelivery.eventType}</dd></div><div><dt>Event ID</dt><dd><code>{selectedDelivery.eventId}</code></dd></div><div><dt>Endpoint URL</dt><dd>{selectedDelivery.endpointUrl}</dd></div><div><dt>Attempts</dt><dd>{selectedDelivery.attemptCount} total / {selectedDelivery.currentRunAttemptCount} current run</dd></div><div><dt>Next retry</dt><dd>{formatDate(selectedDelivery.nextRetryAt)}</dd></div><div><dt>Created</dt><dd>{formatDate(selectedDelivery.createdAt)}</dd></div><div><dt>Updated</dt><dd>{formatDate(selectedDelivery.updatedAt)}</dd></div><div className="detail-wide"><dt>Delivery ID</dt><dd><code>{selectedDelivery.id}</code></dd></div></dl>
                <h3 className="attempt-heading">Attempt history</h3>{deliveryDetail.data.attempts.length === 0 ? <p className="muted">No HTTP attempts recorded yet.</p> : <div className="attempt-table-wrap"><table className="attempt-table"><thead><tr><th scope="col">#</th><th scope="col">Outcome</th><th scope="col">HTTP</th><th scope="col">Error</th><th scope="col">Started</th><th scope="col">Completed</th></tr></thead><tbody>{deliveryDetail.data.attempts.map((attempt) => <tr key={attempt.id}><td>{attempt.attemptNumber}</td><td>{attempt.outcome.replaceAll('_', ' ').toLowerCase()}</td><td>{attempt.httpStatus ?? '—'}</td><td>{attempt.errorCode ?? '—'}</td><td>{formatDate(attempt.startedAt)}</td><td>{formatDate(attempt.completedAt)}</td></tr>)}</tbody></table></div>}
              </>}
            </div>}
          </div>}
          {deliveryList.kind === 'ready' && deliveryList.data.totalPages > 1 && <div className="pagination"><span className="muted">Page {deliveryList.data.page + 1} of {deliveryList.data.totalPages}</span><span><button type="button" className="secondary-button" disabled={deliveryList.data.page === 0} onClick={() => setDeliveryPageNumber(deliveryList.data.page - 1)}>Previous</button>{' '}<button type="button" className="secondary-button" disabled={deliveryList.data.page + 1 >= deliveryList.data.totalPages} onClick={() => setDeliveryPageNumber(deliveryList.data.page + 1)}>Next</button></span></div>}
        </section>
      </div>
    </main>
  );
}
