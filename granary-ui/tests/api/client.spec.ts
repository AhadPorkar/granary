import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, buildQuery, getToken, http, onUnauthorized, request, setToken } from '@/api/client';
import { api } from '@/api';
import { jsonResponse } from '../helpers';

const fetchMock = vi.fn<typeof fetch>();

describe('API client', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockReset();
    setToken(null);
    onUnauthorized(null);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('adds the Bearer token and JSON headers', async () => {
    setToken('abc');
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));
    await http.post('/repositories', { name: 'x' });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/v1/repositories');
    expect(init?.method).toBe('POST');
    const headers = init?.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer abc');
    expect(headers['Content-Type']).toBe('application/json');
    expect(headers.Accept).toBe('application/json');
    expect(init?.body).toBe(JSON.stringify({ name: 'x' }));
  });

  it('sends no Authorization header without a token or for session-less calls', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ name: 'Granary' }));
    await api.system.info();
    expect((fetchMock.mock.calls[0][1]?.headers as Record<string, string>).Authorization).toBeUndefined();

    setToken('abc');
    fetchMock.mockResolvedValue(jsonResponse({ token: 't' }));
    await api.auth.login('a', 'b');
    expect((fetchMock.mock.calls[1][1]?.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it('serialises query parameters and skips empty ones', async () => {
    expect(buildQuery({ q: 'spring boot', page: 0, repository: undefined, prefix: '' })).toBe('?q=spring+boot&page=0');
    expect(buildQuery(undefined)).toBe('');
    fetchMock.mockResolvedValue(jsonResponse({ items: [], total: 0, page: 0, size: 50 }));
    await api.search('x', undefined, 2, 10);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/search?q=x&page=2&size=10');
  });

  it('returns the parsed JSON body and undefined for 204', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse([{ id: 'maven2', displayName: 'Maven' }]));
    expect(await api.formats()).toEqual([{ id: 'maven2', displayName: 'Maven' }]);
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    expect(await api.repositories.delete('x')).toBeUndefined();
  });

  it('maps an error body to ApiError with code, message, details and status', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ code: 'repository.name.taken', message: 'taken', details: { name: 'x' } }, 409));
    const error = await api.repositories.create({ name: 'x', format: 'maven2', type: 'HOSTED' }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.code).toBe('repository.name.taken');
    expect(apiError.message).toBe('taken');
    expect(apiError.details).toEqual({ name: 'x' });
    expect(apiError.status).toBe(409);
  });

  it('falls back to a synthetic code when the error body is not JSON', async () => {
    fetchMock.mockResolvedValue(new Response('Bad gateway', { status: 502, headers: { 'content-type': 'text/plain' } }));
    const error = (await request('GET', '/x').catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('http.502');
    expect(error.status).toBe(502);
    expect(error.message).toBe('Bad gateway');
  });

  it('a 401 on a session call clears the token and notifies the unauthorized handler', async () => {
    const handler = vi.fn();
    onUnauthorized(handler);
    setToken('expired');
    fetchMock.mockResolvedValue(jsonResponse({ code: 'auth.required', message: 'Authentication required' }, 401));
    await expect(api.auth.me()).rejects.toMatchObject({ code: 'auth.required', status: 401 });
    expect(handler).toHaveBeenCalledTimes(1);
    expect(getToken()).toBeNull();
  });

  it('a 401 from the login endpoint does not touch the session handler', async () => {
    const handler = vi.fn();
    onUnauthorized(handler);
    fetchMock.mockResolvedValue(jsonResponse({ code: 'auth.invalid', message: 'Invalid username or password' }, 401));
    await expect(api.auth.login('a', 'b')).rejects.toMatchObject({ code: 'auth.invalid' });
    expect(handler).not.toHaveBeenCalled();
  });

  it('turns a network failure into an ApiError with code "network"', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    const error = (await api.formats().catch((e: unknown) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('network');
    expect(error.status).toBe(0);
  });
});
