import { http } from './client';
import type {
  AssetView,
  ComponentView,
  ConnectSnippet,
  CreateUserRequest,
  FormatView,
  IssuedToken,
  LoginResponse,
  Page,
  PrincipalView,
  RepositoryRequest,
  RepositoryView,
  StandardRepositoriesResult,
  RoleRequest,
  RoleView,
  Settings,
  SettingsRequest,
  SystemInfo,
  SystemStatus,
  TokenView,
  UpdateUserRequest,
  UserView,
} from './types';

const enc = encodeURIComponent;

export const api = {
  auth: {
    login: (username: string, password: string) =>
      http.post<LoginResponse>('/auth/login', { username, password }, { session: false }),
    me: () => http.get<PrincipalView>('/auth/me'),
    changePassword: (currentPassword: string, newPassword: string) =>
      http.post<void>('/auth/password', { currentPassword, newPassword }),
  },

  system: {
    info: () => http.get<SystemInfo>('/system/info', { session: false }),
    status: () => http.get<SystemStatus>('/system/status'),
    settings: () => http.get<Settings>('/system/settings'),
    updateSettings: (body: SettingsRequest) => http.put<Settings>('/system/settings', body),
  },

  search: (q: string, repository: string | undefined, page = 0, size = 50) =>
    http.get<Page<ComponentView>>('/search', { query: { q, repository, page, size } }),

  formats: () => http.get<FormatView[]>('/formats'),

  repositories: {
    list: () => http.get<RepositoryView[]>('/repositories'),
    get: (name: string) => http.get<RepositoryView>(`/repositories/${enc(name)}`),
    connect: (name: string) => http.get<ConnectSnippet[]>(`/repositories/${enc(name)}/connect`),
    create: (body: RepositoryRequest) => http.post<RepositoryView>('/repositories', body),
    update: (name: string, body: RepositoryRequest) => http.put<RepositoryView>(`/repositories/${enc(name)}`, body),
    delete: (name: string) => http.delete(`/repositories/${enc(name)}`),
    setOffline: (name: string, offline: boolean) =>
      http.post<RepositoryView>(`/repositories/${enc(name)}/offline`, { offline }),
    invalidate: (name: string) => http.post<RepositoryView>(`/repositories/${enc(name)}/invalidate`),
    createStandard: () => http.post<StandardRepositoriesResult>('/repositories/standard'),
    components: (name: string, q = '', page = 0, size = 50) =>
      http.get<Page<ComponentView>>(`/repositories/${enc(name)}/components`, { query: { q, page, size } }),
    componentAssets: (name: string, componentId: string) =>
      http.get<AssetView[]>(`/repositories/${enc(name)}/components/${enc(componentId)}/assets`),
    deleteComponent: (name: string, componentId: string) =>
      http.delete<{ deletedAssets: number }>(`/repositories/${enc(name)}/components/${enc(componentId)}`),
    assets: (name: string, prefix = '', page = 0, size = 50) =>
      http.get<Page<AssetView>>(`/repositories/${enc(name)}/assets`, { query: { prefix, page, size } }),
    deleteAsset: (name: string, assetId: string) => http.delete(`/repositories/${enc(name)}/assets/${enc(assetId)}`),
  },

  users: {
    list: () => http.get<UserView[]>('/users'),
    get: (username: string) => http.get<UserView>(`/users/${enc(username)}`),
    create: (body: CreateUserRequest) => http.post<UserView>('/users', body),
    update: (username: string, body: UpdateUserRequest) => http.put<UserView>(`/users/${enc(username)}`, body),
    setPassword: (username: string, password: string) => http.post<void>(`/users/${enc(username)}/password`, { password }),
    delete: (username: string) => http.delete(`/users/${enc(username)}`),
  },

  roles: {
    list: () => http.get<RoleView[]>('/roles'),
    create: (body: RoleRequest) => http.post<RoleView>('/roles', body),
    update: (name: string, body: RoleRequest) => http.put<RoleView>(`/roles/${enc(name)}`, body),
    delete: (name: string) => http.delete(`/roles/${enc(name)}`),
    privileges: () => http.get<string[]>('/privileges'),
  },

  tokens: {
    list: () => http.get<TokenView[]>('/tokens'),
    create: (name: string, expiresAt: string | null) => http.post<IssuedToken>('/tokens', { name, expiresAt }),
    revoke: (id: string) => http.delete(`/tokens/${enc(id)}`),
  },
};

export type Api = typeof api;
