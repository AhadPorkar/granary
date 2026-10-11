/* Mirrors granary-server's Dtos.java. Secrets never travel back from the server; null fields are omitted from the JSON (Jackson non_null), hence optional. */

export type RepositoryType = 'HOSTED' | 'PROXY' | 'GROUP';
export type VersionPolicy = 'RELEASE' | 'SNAPSHOT' | 'MIXED';
export type AssetOrigin = 'UPLOADED' | 'CACHED' | 'GENERATED';

export interface UserView {
  id: string;
  username: string;
  displayName?: string | null;
  email?: string | null;
  enabled: boolean;
  builtin: boolean;
  roles: string[];
  createdAt: string;
}

export interface LoginResponse {
  token: string;
  expiresAt: string;
  user: UserView;
}

export interface PrincipalView {
  username: string;
  anonymous: boolean;
  roles: string[];
  privileges: string[];
}

export type SettingType = 'STRING' | 'BOOLEAN' | 'INTEGER' | 'URL';

/** One format-specific repository setting, described by the server so the form can render an editor for it. */
export interface SettingDescriptor {
  key: string;
  label: string;
  type: SettingType;
  defaultValue?: string | null;
  description?: string | null;
  appliesTo: RepositoryType[];
}

export interface FormatView {
  id: string;
  displayName: string;
  /** Repository types the format supports; the form offers only these. */
  types: RepositoryType[];
  settings: SettingDescriptor[];
  /** Names of the standard repositories created by POST /repositories/standard. */
  templates: string[];
}

export type SnippetLanguage = 'shell' | 'ini' | 'xml' | 'json' | 'yaml' | 'toml' | 'text';

/** Ready-to-paste client configuration rendered by the repository's format on the server. */
export interface ConnectSnippet {
  id: string;
  title: string;
  language: SnippetLanguage;
  filename?: string | null;
  code: string;
}

export interface UpstreamView {
  reachable: boolean;
  consecutiveFailures: number;
  lastError?: string | null;
  lastSuccessAt?: string | null;
  lastFailureAt?: string | null;
  retryAt?: string | null;
}

export interface RepositoryStatistics {
  assets: number;
  components: number;
  bytes: number;
}

export interface RepositoryView {
  id: string;
  name: string;
  format: string;
  type: RepositoryType;
  online: boolean;
  blobStore: string;
  description?: string | null;
  allowRedeploy: boolean;
  versionPolicy: VersionPolicy;
  remoteUrl?: string | null;
  remoteUsername?: string | null;
  hasRemotePassword: boolean;
  hasRemoteToken: boolean;
  connectTimeoutSeconds?: number | null;
  readTimeoutSeconds?: number | null;
  metadataMaxAgeMinutes?: number | null;
  negativeCacheMinutes?: number | null;
  offlineMode: boolean;
  members: string[];
  formatSettings?: Record<string, string>;
  url: string;
  createdAt: string;
  updatedAt: string;
  statistics: RepositoryStatistics;
  upstream?: UpstreamView | null;
}

export interface RepositoryRequest {
  name: string;
  format: string;
  type: RepositoryType;
  online?: boolean;
  blobStore?: string;
  description?: string | null;
  allowRedeploy?: boolean;
  versionPolicy?: VersionPolicy;
  remoteUrl?: string | null;
  remoteUsername?: string | null;
  remotePassword?: string | null;
  remoteToken?: string | null;
  connectTimeoutSeconds?: number | null;
  readTimeoutSeconds?: number | null;
  metadataMaxAgeMinutes?: number | null;
  negativeCacheMinutes?: number | null;
  offlineMode?: boolean;
  members?: string[];
  formatSettings?: Record<string, string>;
}

export interface ComponentView {
  id: string;
  repositoryId: string;
  repository: string;
  format: string;
  namespace: string;
  name: string;
  version: string;
  createdAt: string;
  updatedAt: string;
}

export interface AssetView {
  id: string;
  repositoryId: string;
  componentId?: string | null;
  path: string;
  sha256?: string | null;
  size: number;
  contentType?: string | null;
  origin: AssetOrigin;
  metadata: boolean;
  downloadCount: number;
  lastDownloadedAt?: string | null;
  lastVerifiedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface CreateUserRequest {
  username: string;
  password: string;
  displayName?: string | null;
  email?: string | null;
  roles?: string[];
}

export interface UpdateUserRequest {
  displayName?: string | null;
  email?: string | null;
  enabled?: boolean;
  roles?: string[];
}

export interface RoleView {
  id: string;
  name: string;
  description?: string | null;
  builtin: boolean;
  privileges: string[];
}

export interface RoleRequest {
  name: string;
  description?: string | null;
  privileges: string[];
}

export interface TokenView {
  id: string;
  name: string;
  prefix: string;
  createdAt: string;
  expiresAt?: string | null;
  lastUsedAt?: string | null;
}

export interface IssuedToken {
  token: TokenView;
  secret: string;
}

export interface SystemInfo {
  name: string;
  version: string;
  formats: string[];
  anonymousRead: boolean;
  offlineMode: boolean;
}

export interface BlobStoreStatus {
  name: string;
  blobCount: number;
  totalBytes: number;
  availableBytes: number;
}

/** Outcome of compacting one blob store ({@code POST /blob-stores/{name}/compact}). */
export interface CompactionReport {
  blobStore: string;
  startedAt: string;
  durationMillis: number;
  /** ISO-8601 duration: blobs written more recently than this were kept regardless of references. */
  gracePeriod: string;
  blobsScanned: number;
  blobsDeleted: number;
  bytesReclaimed: number;
}

export interface UpstreamStatus {
  repository: string;
  remoteUrl: string;
  offlineMode: boolean;
  status: UpstreamView;
}

export interface Settings {
  anonymousRead: boolean;
  offlineMode: boolean;
  baseUrl: string;
}

export interface SystemStatus extends SystemInfo {
  blobStores: BlobStoreStatus[];
  upstreams: UpstreamStatus[];
  repositories: number;
  settings: Settings;
  defaultAdminPassword: boolean;
  jvm: {
    version: string;
    maxMemory: number;
    totalMemory: number;
    freeMemory: number;
    processors: number;
  };
}

export interface SettingsRequest {
  anonymousRead?: boolean;
  offlineMode?: boolean;
  baseUrl?: string;
}

/** Result of creating the conventional proxy/hosted/group repositories for every installed format. */
export interface StandardRepositoriesResult {
  created: string[];
  templates: string[];
}
