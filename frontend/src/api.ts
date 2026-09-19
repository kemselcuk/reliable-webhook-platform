import { parseProblemDetail, userMessageForProblem } from './api-helpers.js';

export type HealthResponse = {
  status: string;
  service: string;
};

export type DeliveryStatus = 'PENDING' | 'PROCESSING' | 'RETRY_SCHEDULED' | 'SUCCESS' | 'FAILED' | 'DEAD';

export type WebhookEndpoint = {
  id: string;
  name: string;
  url: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
};

export type WebhookEndpointPage = {
  items: WebhookEndpoint[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type CreateEventResponse = {
  id: string;
  type: string;
  payload: unknown;
  deliveryIds: string[];
  createdAt: string;
};

export type DeliveryListItem = {
  id: string;
  eventId: string;
  eventType: string;
  endpointId: string;
  endpointName: string;
  endpointUrl: string;
  status: DeliveryStatus;
  attemptCount: number;
  currentRunAttemptCount: number;
  nextRetryAt: string | null;
  createdAt: string;
  updatedAt: string;
  replayable: boolean;
};

export type DeliveryAttempt = {
  id: string;
  attemptNumber: number;
  outcome: 'SUCCESS' | 'RETRYABLE_FAILURE' | 'PERMANENT_FAILURE';
  httpStatus: number | null;
  errorCode: string | null;
  startedAt: string;
  completedAt: string;
};

export type DeliveryPage = {
  items: DeliveryListItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type DeliveryDetail = {
  delivery: DeliveryListItem;
  attempts: DeliveryAttempt[];
};

export type DeliveryReplayResponse = {
  deliveryId: string;
  status: DeliveryStatus;
  attemptCount: number;
  currentRunAttemptCount: number;
};

export type SystemSummary = {
  status: string;
  service: string;
  generatedAt: string;
  endpointCount: number;
  eventCount: number;
  pendingOutbox: number;
  retryBacklog: number;
  acceptedEvents: number;
  deliveryIntents: number;
  deliveriesByStatus: Record<string, number>;
};

export type ProblemDetail = {
  title?: string;
  detail?: string;
  code?: string;
  fieldErrors?: Record<string, string>;
};

export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail | null;

  constructor(message: string, status: number, problem: ProblemDetail | null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }
}

function parseResponseBody(text: string): unknown {
  if (!text.trim()) {
    return null;
  }

  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    const headers = new Headers(init.headers);
    headers.set('Accept', 'application/json');
    if (init.body && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }

    response = await fetch(path, { ...init, headers });
  } catch (error: unknown) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error;
    }
    throw new Error('Could not reach the backend. Check that the local API is running.', { cause: error });
  }

  const body = parseResponseBody(await response.text());
  if (!response.ok) {
    const problem = parseProblemDetail(body);
    throw new ApiError(
      userMessageForProblem(problem, `The request failed (HTTP ${response.status}).`),
      response.status,
      problem,
    );
  }

  if (body === null) {
    throw new Error('The backend returned an empty response.');
  }

  return body as T;
}

export function loadHealth(signal?: AbortSignal): Promise<HealthResponse> {
  return request<HealthResponse>('/api/system/health', { signal });
}

export function loadEndpoints(
  page = 0,
  size = 100,
  signal?: AbortSignal,
): Promise<WebhookEndpointPage> {
  return request<WebhookEndpointPage>(`/api/webhook-endpoints?page=${page}&size=${size}`, { signal });
}

export function createEndpoint(name: string, url: string, secret: string): Promise<WebhookEndpoint> {
  return request<WebhookEndpoint>('/api/webhook-endpoints', {
    method: 'POST',
    body: JSON.stringify({ name, url, secret }),
  });
}

export function setEndpointEnabled(endpointId: string, enabled: boolean): Promise<WebhookEndpoint> {
  return request<WebhookEndpoint>(`/api/webhook-endpoints/${encodeURIComponent(endpointId)}/enabled`, {
    method: 'PATCH',
    body: JSON.stringify({ enabled }),
  });
}

export function createEvent(
  type: string,
  payload: Record<string, unknown>,
  endpointIds: string[],
  idempotencyKey?: string,
): Promise<CreateEventResponse> {
  const headers = idempotencyKey?.trim() ? { 'Idempotency-Key': idempotencyKey.trim() } : undefined;
  return request<CreateEventResponse>('/api/events', {
    method: 'POST',
    headers,
    body: JSON.stringify({ type, payload, endpointIds }),
  });
}

export function loadDeliveries(
  page = 0,
  size = 20,
  status?: DeliveryStatus,
  signal?: AbortSignal,
): Promise<DeliveryPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) {
    params.set('status', status);
  }
  return request<DeliveryPage>(`/api/deliveries?${params.toString()}`, { signal });
}

export function loadDelivery(deliveryId: string, signal?: AbortSignal): Promise<DeliveryDetail> {
  return request<DeliveryDetail>(`/api/deliveries/${encodeURIComponent(deliveryId)}`, { signal });
}

export function replayDelivery(deliveryId: string): Promise<DeliveryReplayResponse> {
  return request<DeliveryReplayResponse>(
    `/api/deliveries/${encodeURIComponent(deliveryId)}/replay`,
    { method: 'POST' },
  );
}

export function loadSystemSummary(signal?: AbortSignal): Promise<SystemSummary> {
  return request<SystemSummary>('/api/system/summary', { signal });
}
