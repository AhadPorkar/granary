import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { api } from '@/api';
import { getToken, setToken } from '@/api/client';
import type { PrincipalView, UserView } from '@/api/types';

export const USER_STORAGE_KEY = 'granary.user';

export const SYSTEM_ADMIN = 'system:admin';
export const REPOSITORIES_MANAGE = 'repositories:manage';
export const USERS_MANAGE = 'users:manage';
export const SETTINGS_MANAGE = 'settings:manage';
export const REPOSITORY_READ_ALL = 'repository:read:*';
export const REPOSITORY_WRITE_ALL = 'repository:write:*';

function readStoredUser(): UserView | null {
  try {
    const raw = globalThis.localStorage?.getItem(USER_STORAGE_KEY);
    return raw ? (JSON.parse(raw) as UserView) : null;
  } catch {
    return null;
  }
}

function storeUser(user: UserView | null): void {
  try {
    if (user) {
      globalThis.localStorage?.setItem(USER_STORAGE_KEY, JSON.stringify(user));
    } else {
      globalThis.localStorage?.removeItem(USER_STORAGE_KEY);
    }
  } catch {
    /* ignore */
  }
}

/** Pure privilege logic, shared with the server's Privileges.java semantics. */
export function hasPrivilege(privileges: readonly string[], privilege: string): boolean {
  return privileges.includes(SYSTEM_ADMIN) || privileges.includes(privilege);
}

export function canReadRepository(privileges: readonly string[], name: string): boolean {
  return (
    privileges.includes(SYSTEM_ADMIN) ||
    privileges.includes(REPOSITORY_READ_ALL) ||
    privileges.includes(REPOSITORY_WRITE_ALL) ||
    privileges.includes(`repository:read:${name}`) ||
    privileges.includes(`repository:write:${name}`)
  );
}

export function canWriteRepository(privileges: readonly string[], name: string): boolean {
  return (
    privileges.includes(SYSTEM_ADMIN) ||
    privileges.includes(REPOSITORY_WRITE_ALL) ||
    privileges.includes(`repository:write:${name}`)
  );
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getToken());
  const user = ref<UserView | null>(readStoredUser());
  const username = ref<string | null>(user.value?.username ?? null);
  const roles = ref<string[]>(user.value?.roles ?? []);
  const privileges = ref<string[]>([]);
  const restored = ref(false);
  let restoring: Promise<void> | null = null;

  const isAuthenticated = computed(() => token.value !== null && username.value !== null);
  const displayName = computed(() => user.value?.displayName || username.value || '');

  function can(privilege: string): boolean {
    return hasPrivilege(privileges.value, privilege);
  }

  function canRead(repository: string): boolean {
    return canReadRepository(privileges.value, repository);
  }

  function canWrite(repository: string): boolean {
    return canWriteRepository(privileges.value, repository);
  }

  function applyPrincipal(principal: PrincipalView): void {
    username.value = principal.username;
    roles.value = [...principal.roles];
    privileges.value = [...principal.privileges];
  }

  function clearSession(): void {
    setToken(null);
    storeUser(null);
    token.value = null;
    user.value = null;
    username.value = null;
    roles.value = [];
    privileges.value = [];
    restored.value = true;
    restoring = null;
  }

  async function login(name: string, password: string): Promise<UserView> {
    const response = await api.auth.login(name, password);
    setToken(response.token);
    token.value = response.token;
    user.value = response.user;
    storeUser(response.user);
    username.value = response.user.username;
    roles.value = [...response.user.roles];
    const me = await api.auth.me();
    applyPrincipal(me);
    restored.value = true;
    return response.user;
  }

  /** Re-validates a token found in storage; resolves once the privileges are known (or the session is gone). */
  function restore(): Promise<void> {
    if (restored.value) {
      return Promise.resolve();
    }
    if (!restoring) {
      restoring = (async () => {
        if (!token.value) {
          clearSession();
          return;
        }
        try {
          applyPrincipal(await api.auth.me());
          restored.value = true;
        } catch {
          clearSession();
        }
      })();
    }
    return restoring;
  }

  function logout(): void {
    clearSession();
  }

  return {
    token,
    user,
    username,
    roles,
    privileges,
    restored,
    isAuthenticated,
    displayName,
    can,
    canRead,
    canWrite,
    login,
    restore,
    logout,
    clearSession,
  };
});
