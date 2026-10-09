package dev.granary.server.api;

import dev.granary.core.model.ApiToken;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.model.Role;
import dev.granary.core.model.User;
import dev.granary.core.model.VersionPolicy;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.security.GranaryPrincipal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Request and response bodies of the REST API. Secrets never leave the server. */
public final class Dtos {

    private Dtos() {
    }

    // --- auth ---

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, Instant expiresAt, UserView user) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record PrincipalView(String username, boolean anonymous, Set<String> roles, Set<String> privileges) {
        public static PrincipalView of(GranaryPrincipal p) {
            return new PrincipalView(p.username(), p.anonymous(), p.roles(), p.privileges());
        }
    }

    // --- repositories ---

    public record RepositoryRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank String format,
            @NotNull RepositoryType type,
            Boolean online,
            String blobStore,
            @Size(max = 500) String description,
            Boolean allowRedeploy,
            VersionPolicy versionPolicy,
            String remoteUrl,
            String remoteUsername,
            String remotePassword,
            String remoteToken,
            Integer connectTimeoutSeconds,
            Integer readTimeoutSeconds,
            Integer metadataMaxAgeMinutes,
            Integer negativeCacheMinutes,
            Boolean offlineMode,
            List<String> members,
            Map<String, String> formatSettings) {

        public Repository toEntity() {
            Repository r = new Repository();
            r.setName(name);
            r.setFormat(format);
            r.setType(type);
            r.setOnline(online == null || online);
            r.setBlobStore(blobStore == null || blobStore.isBlank() ? "default" : blobStore);
            r.setDescription(description);
            r.setAllowRedeploy(Boolean.TRUE.equals(allowRedeploy));
            r.setVersionPolicy(versionPolicy == null ? VersionPolicy.MIXED : versionPolicy);
            r.setRemoteUrl(blankToNull(remoteUrl));
            r.setRemoteUsername(blankToNull(remoteUsername));
            r.setRemotePassword(remotePassword);
            r.setRemoteToken(remoteToken);
            r.setConnectTimeoutSeconds(connectTimeoutSeconds);
            r.setReadTimeoutSeconds(readTimeoutSeconds);
            r.setMetadataMaxAgeMinutes(metadataMaxAgeMinutes);
            r.setNegativeCacheMinutes(negativeCacheMinutes);
            r.setOfflineMode(Boolean.TRUE.equals(offlineMode));
            r.setMembers(members == null ? List.of() : members);
            r.setFormatSettings(formatSettings == null ? Map.of() : formatSettings);
            return r;
        }
    }

    public record UpstreamView(boolean reachable, int consecutiveFailures, String lastError,
                               Instant lastSuccessAt, Instant lastFailureAt, Instant retryAt) {
        public static UpstreamView of(UpstreamHealth.Status s) {
            return new UpstreamView(s.reachable(), s.consecutiveFailures(), s.lastError(),
                    s.lastSuccessAt(), s.lastFailureAt(), s.retryAt());
        }
    }

    public record RepositoryView(
            UUID id, String name, String format, RepositoryType type, boolean online, String blobStore,
            String description, boolean allowRedeploy, VersionPolicy versionPolicy, String remoteUrl,
            String remoteUsername, boolean hasRemotePassword, boolean hasRemoteToken,
            Integer connectTimeoutSeconds, Integer readTimeoutSeconds, Integer metadataMaxAgeMinutes,
            Integer negativeCacheMinutes, boolean offlineMode, List<String> members, Map<String, String> formatSettings,
            String url, Instant createdAt, Instant updatedAt, Map<String, Object> statistics, UpstreamView upstream) {

        public static RepositoryView of(Repository r, String url, Map<String, Object> statistics, UpstreamView upstream) {
            return new RepositoryView(r.getId(), r.getName(), r.getFormat(), r.getType(), r.isOnline(),
                    r.getBlobStore(), r.getDescription(), r.isAllowRedeploy(), r.getVersionPolicy(), r.getRemoteUrl(),
                    r.getRemoteUsername(), r.getRemotePassword() != null && !r.getRemotePassword().isEmpty(),
                    r.getRemoteToken() != null && !r.getRemoteToken().isEmpty(),
                    r.getConnectTimeoutSeconds(), r.getReadTimeoutSeconds(), r.getMetadataMaxAgeMinutes(),
                    r.getNegativeCacheMinutes(), r.isOfflineMode(), r.getMembers(), r.getFormatSettings(), url,
                    r.getCreatedAt(), r.getUpdatedAt(), statistics, upstream);
        }
    }

    public record OfflineRequest(boolean offline) {
    }

    public record FormatView(String id, String displayName, Set<RepositoryType> types,
                             List<SettingDescriptor> settings, List<String> templates) {
    }

    public record ComponentView(UUID id, UUID repositoryId, String repository, String format, String namespace,
                                String name, String version, Instant createdAt, Instant updatedAt) {
        public static ComponentView of(Component c, String repositoryName) {
            return new ComponentView(c.getId(), c.getRepositoryId(), repositoryName, c.getFormat(), c.getNamespace(),
                    c.getName(), c.getVersion(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    public record AssetView(UUID id, UUID repositoryId, UUID componentId, String path, String sha256, long size,
                            String contentType, Asset.Origin origin, boolean metadata, long downloadCount,
                            Instant lastDownloadedAt, Instant lastVerifiedAt, Instant createdAt, Instant updatedAt) {
        public static AssetView of(Asset a) {
            return new AssetView(a.getId(), a.getRepositoryId(), a.getComponentId(), a.getPath(), a.getBlobSha256(),
                    a.getSize(), a.getContentType(), a.getOrigin(), a.isMetadata(), a.getDownloadCount(),
                    a.getLastDownloadedAt(), a.getLastVerifiedAt(), a.getCreatedAt(), a.getUpdatedAt());
        }
    }

    public record Page<T>(List<T> items, long total, int page, int size) {
    }

    // --- users, roles, tokens ---

    public record UserView(UUID id, String username, String displayName, String email, boolean enabled,
                           boolean builtin, Set<String> roles, Instant createdAt) {
        public static UserView of(User u) {
            return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(), u.isEnabled(),
                    u.isBuiltin(), u.getRoles().stream().map(Role::getName).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)),
                    u.getCreatedAt());
        }
    }

    public record CreateUserRequest(@NotBlank @Size(max = 100) String username, @NotBlank String password,
                                    @Size(max = 200) String displayName, @Size(max = 320) String email,
                                    Set<String> roles) {
    }

    public record UpdateUserRequest(@Size(max = 200) String displayName, @Size(max = 320) String email,
                                    Boolean enabled, Set<String> roles) {
    }

    public record SetPasswordRequest(@NotBlank String password) {
    }

    public record RoleView(UUID id, String name, String description, boolean builtin, Set<String> privileges) {
        public static RoleView of(Role r) {
            return new RoleView(r.getId(), r.getName(), r.getDescription(), r.isBuiltin(), r.getPrivileges());
        }
    }

    public record RoleRequest(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description,
                              Set<String> privileges) {
    }

    public record TokenView(UUID id, String name, String prefix, Instant createdAt, Instant expiresAt, Instant lastUsedAt) {
        public static TokenView of(ApiToken t) {
            return new TokenView(t.getId(), t.getName(), t.getTokenPrefix(), t.getCreatedAt(), t.getExpiresAt(), t.getLastUsedAt());
        }
    }

    public record CreateTokenRequest(@NotBlank @Size(max = 200) String name, Instant expiresAt) {
    }

    public record IssuedTokenView(TokenView token, String secret) {
    }

    // --- system ---

    public record SettingsRequest(Boolean anonymousRead, Boolean offlineMode, String baseUrl) {
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
