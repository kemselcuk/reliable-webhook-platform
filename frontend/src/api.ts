import { parseProblemDetail, userMessageForProblem } from './api-helpers.js';

export type HealthResponse = {
  status: string;
  service: string;
};

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

export function createEndpoint(name: string, url: string): Promise<WebhookEndpoint> {
  return request<WebhookEndpoint>('/api/webhook-endpoints', {
    method: 'POST',
    body: JSON.stringify({ name, url }),
  });
}

export function createEvent(
  type: string,
  payload: Record<string, unknown>,
  endpointIds: string[],
): Promise<CreateEventResponse> {
  return request<CreateEventResponse>('/api/events', {
    method: 'POST',
    body: JSON.stringify({ type, payload, endpointIds }),
  });
}
