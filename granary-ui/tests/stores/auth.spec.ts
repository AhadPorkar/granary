import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/api', () => ({
  api: {
    auth: {
      login: vi.fn(),
      me: vi.fn(),
    },
  },
}));

import { api } from '@/api';
import { TOKEN_STORAGE_KEY, getToken, setToken } from '@/api/client';
import { USER_STORAGE_KEY, canReadRepository, canWriteRepository, hasPrivilege, useAuthStore } from '@/stores/auth';
import { freshPinia } from '../helpers';

const user = {
  id: '1',
  username: 'alice',
  displayName: 'Alice',
  email: null,
  enabled: true,
  builtin: false,
  roles: ['developer'],
  createdAt: '2026-01-01T00:00:00Z',
};

describe('auth store', () => {
  beforeEach(() => {
    setToken(null);
    localStorage.clear();
    freshPinia();
  });

  it('login stores the token, the user and the privileges reported by /auth/me', async () => {
    vi.mocked(api.auth.login).mockResolvedValue({ token: 'jwt-123', expiresAt: '2026-12-31T00:00:00Z', user });
    vi.mocked(api.auth.me).mockResolvedValue({ username: 'alice', anonymous: false, roles: ['developer'], privileges: ['repository:read:*', 'repository:write:*'] });

    const auth = useAuthStore();
    await auth.login('alice', 'secret');

    expect(api.auth.login).toHaveBeenCalledWith('alice', 'secret');
    expect(auth.token).toBe('jwt-123');
    expect(getToken()).toBe('jwt-123');
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('jwt-123');
    expect(JSON.parse(localStorage.getItem(USER_STORAGE_KEY) ?? '{}')).toMatchObject({ username: 'alice' });
    expect(auth.isAuthenticated).toBe(true);
    expect(auth.displayName).toBe('Alice');
    expect(auth.privileges).toEqual(['repository:read:*', 'repository:write:*']);
  });

  it('a failed login leaves no session behind', async () => {
    vi.mocked(api.auth.login).mockRejectedValue(new Error('auth.invalid'));
    const auth = useAuthStore();
    await expect(auth.login('alice', 'wrong')).rejects.toThrow();
    expect(auth.isAuthenticated).toBe(false);
    expect(getToken()).toBeNull();
  });

  it('can() treats system:admin as implying every privilege', () => {
    const auth = useAuthStore();
    auth.privileges = ['system:admin'];
    expect(auth.can('users:manage')).toBe(true);
    expect(auth.can('settings:manage')).toBe(true);
    expect(auth.can('anything:else')).toBe(true);
    expect(auth.canRead('maven-central')).toBe(true);
    expect(auth.canWrite('maven-central')).toBe(true);
  });

  it('can() otherwise requires the exact privilege', () => {
    const auth = useAuthStore();
    auth.privileges = ['users:manage'];
    expect(auth.can('users:manage')).toBe(true);
    expect(auth.can('settings:manage')).toBe(false);
    expect(auth.can('repositories:manage')).toBe(false);
    expect(hasPrivilege([], 'users:manage')).toBe(false);
  });

  it('canRead/canWrite follow the wildcard and per-repository rules; write implies read', () => {
    expect(canReadRepository(['repository:read:*'], 'npm-proxy')).toBe(true);
    expect(canWriteRepository(['repository:read:*'], 'npm-proxy')).toBe(false);
    expect(canReadRepository(['repository:write:*'], 'npm-proxy')).toBe(true);
    expect(canReadRepository(['repository:read:npm-proxy'], 'npm-proxy')).toBe(true);
    expect(canReadRepository(['repository:read:npm-proxy'], 'other')).toBe(false);
    expect(canWriteRepository(['repository:write:npm-proxy'], 'npm-proxy')).toBe(true);
    expect(canReadRepository(['repository:write:npm-proxy'], 'npm-proxy')).toBe(true);
    expect(canWriteRepository(['repository:read:npm-proxy'], 'npm-proxy')).toBe(false);
  });

  it('logout clears the token, the stored user and the privileges', async () => {
    vi.mocked(api.auth.login).mockResolvedValue({ token: 'jwt-123', expiresAt: '2026-12-31T00:00:00Z', user });
    vi.mocked(api.auth.me).mockResolvedValue({ username: 'alice', anonymous: false, roles: ['developer'], privileges: ['repository:read:*'] });
    const auth = useAuthStore();
    await auth.login('alice', 'secret');
    auth.logout();
    expect(auth.isAuthenticated).toBe(false);
    expect(auth.privileges).toEqual([]);
    expect(getToken()).toBeNull();
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
    expect(localStorage.getItem(USER_STORAGE_KEY)).toBeNull();
  });

  it('restore() re-validates a stored token through /auth/me and drops it when the server rejects it', async () => {
    setToken('stale');
    vi.mocked(api.auth.me).mockRejectedValueOnce(new Error('401'));
    const rejected = useAuthStore();
    await rejected.restore();
    expect(rejected.isAuthenticated).toBe(false);
    expect(getToken()).toBeNull();

    setToken('fresh');
    freshPinia();
    vi.mocked(api.auth.me).mockResolvedValueOnce({ username: 'bob', anonymous: false, roles: ['reader'], privileges: ['repository:read:*'] });
    const accepted = useAuthStore();
    await accepted.restore();
    expect(accepted.isAuthenticated).toBe(true);
    expect(accepted.username).toBe('bob');
    expect(accepted.can('repository:read:*')).toBe(true);
  });
});
