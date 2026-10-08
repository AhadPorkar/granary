/**
 * Thin fetch wrapper for the Granary REST API.
 *
 * - adds the Bearer session token,
 * - encodes/decodes JSON,
 * - normalises every failure to an {@link ApiError} ({code, message, details, status}),
 * - reports a 401 on an authenticated call through {@link onUnauthorized} so the app can drop the session.
 */

export const API_BASE = '/api/v1';
export const TOKEN_STORAGE_KEY = 'granary.token';

export interface ApiErrorShape {
  code: string;
  message: string;
  details: Record<string, unknown>;
  status: number;
}

export class ApiError extends Error implements ApiErrorShape {
  readonly code: string;
  readonly details: Record<string, unknown>;
  readonly status: number;

  constructor(shape: ApiErrorShape) {
    super(shape.message);
    this.name = 'ApiError';
    this.code = shape.code;
    this.details = shape.details;
    this.status = shape.status;
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

export type QueryValue = string | number | boolean | null | undefined;

export interface RequestOptions {
  body?: unknown;
  query?: Record<string, QueryValue>;
  /** Set to false for calls that must never trigger the unauthorized handler (login, public info). */
  session?: boolean;
  signal?: AbortSignal;
}

let token: string | null = readStoredToken();
let unauthorizedHandler: (() => void) | null = null;

function readStoredToken(): string | null {
  try {
    return globalThis.localStorage?.getItem(TOKEN_STORAGE_KEY) ?? null;
  } catch {
    return null;
  }
}

export function getToken(): string | null {
  return token;
}

export function setToken(value: string | null): void {
  token = value;
  try {
    if (value) {
      globalThis.localStorage?.setItem(TOKEN_STORAGE_KEY, value);
    } else {
      globalThis.localStorage?.removeItem(TOKEN_STORAGE_KEY);
    }
  } catch {
    /* storage unavailable (private mode): keep the token in memory only */
  }
}

/** Registers what happens when the server answers 401 to a call made with a session token. */
export function onUnauthorized(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

export function buildQuery(query?: Record<string, QueryValue>): string {
  if (!query) {
    return '';
  }
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value));
    }
  }
  const s = params.toString();
  return s ? `?${s}` : '';
}

async function parseBody(response: Response): Promise<unknown> {
  if (response.status === 204) {
    return undefined;
  }
  const text = await response.text();
  if (!text) {
    return undefined;
  }
  const type = response.headers.get('content-type') ?? '';
  if (type.includes('json')) {
    try {
      return JSON.parse(text);
    } catch {
      return text;
    }
  }
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function toApiError(status: number, body: unknown): ApiError {
  if (body && typeof body === 'object') {
    const b = body as Record<string, unknown>;
    const code = typeof b.code === 'string' && b.code ? b.code : `http.${status}`;
    const message = typeof b.message === 'string' && b.message ? b.message : `HTTP ${status}`;
    const details = b.details && typeof b.details === 'object' ? (b.details as Record<string, unknown>) : {};
    return new ApiError({ code, message, details, status });
  }
  return new ApiError({ code: `http.${status}`, message: typeof body === 'string' && body ? body : `HTTP ${status}`, details: {}, status });
}

export async function request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const useSession = options.session !== false;
  if (useSession && token) {
    headers.Authorization = `Bearer ${token}`;
  }
  const init: RequestInit = { method, headers, signal: options.signal };
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(options.body);
  }

  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}${buildQuery(options.query)}`, init);
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw e;
    }
    throw new ApiError({ code: 'network', message: e instanceof Error ? e.message : String(e), details: {}, status: 0 });
  }

  const body = await parseBody(response);
  if (response.ok) {
    return body as T;
  }
  const error = toApiError(response.status, body);
  if (response.status === 401 && useSession) {
    setToken(null);
    unauthorizedHandler?.();
  }
  throw error;
}

export const http = {
  get: <T>(path: string, options?: RequestOptions) => request<T>('GET', path, options),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) => request<T>('POST', path, { ...options, body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) => request<T>('PUT', path, { ...options, body }),
  delete: <T = void>(path: string, options?: RequestOptions) => request<T>('DELETE', path, options),
};
