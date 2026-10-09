package dev.granary.core.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A repository definition. One entity covers all three {@link RepositoryType}s; columns that do not
 * apply to a type are simply {@code null} (for example {@code remoteUrl} on a hosted repository).
 */
@Entity
@Table(name = "repositories")
public class Repository {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** Format identifier, e.g. {@code maven2}, {@code npm}, {@code nuget}. */
    @Column(nullable = false, length = 32)
    private String format;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RepositoryType type;

    @Column(nullable = false)
    private boolean online = true;

    @Column(name = "blob_store", nullable = false, length = 64)
    private String blobStore = "default";

    @Column(length = 500)
    private String description;

    // --- hosted ---------------------------------------------------------------------------

    @Column(name = "allow_redeploy", nullable = false)
    private boolean allowRedeploy = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "version_policy", nullable = false, length = 16)
    private VersionPolicy versionPolicy = VersionPolicy.MIXED;

    // --- proxy ----------------------------------------------------------------------------

    @Column(name = "remote_url", length = 1000)
    private String remoteUrl;

    @Column(name = "remote_username", length = 200)
    private String remoteUsername;

    @Column(name = "remote_password", length = 500)
    private String remotePassword;

    /** Bearer token sent to the upstream registry (for example an npm or GitHub Packages token). */
    @Column(name = "remote_token", length = 1000)
    private String remoteToken;

    @Column(name = "connect_timeout_seconds")
    private Integer connectTimeoutSeconds;

    @Column(name = "read_timeout_seconds")
    private Integer readTimeoutSeconds;

    /** Age in minutes after which cached index/metadata documents are re-validated upstream. */
    @Column(name = "metadata_max_age_minutes")
    private Integer metadataMaxAgeMinutes;

    @Column(name = "negative_cache_minutes")
    private Integer negativeCacheMinutes;

    /** When set the proxy never contacts upstream and serves only what is already cached. */
    @Column(name = "offline_mode", nullable = false)
    private boolean offlineMode = false;

    // --- group ----------------------------------------------------------------------------

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "repository_members", joinColumns = @JoinColumn(name = "repository_id"))
    @OrderColumn(name = "position")
    @Column(name = "member_name", nullable = false, length = 100)
    private List<String> members = new ArrayList<>();

    // --- format specific --------------------------------------------------------------------

    /** Free-form settings a format declares through {@code FormatHandler.settings()}; stored as JSON. */
    @Convert(converter = StringMapConverter.class)
    @Column(name = "format_settings", length = 100000)
    private Map<String, String> formatSettings = new LinkedHashMap<>();

    // --- audit ----------------------------------------------------------------------------

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isHosted() {
        return type == RepositoryType.HOSTED;
    }

    public boolean isProxy() {
        return type == RepositoryType.PROXY;
    }

    public boolean isGroup() {
        return type == RepositoryType.GROUP;
    }

    // --- accessors ------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public RepositoryType getType() {
        return type;
    }

    public void setType(RepositoryType type) {
        this.type = type;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public String getBlobStore() {
        return blobStore;
    }

    public void setBlobStore(String blobStore) {
        this.blobStore = blobStore;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isAllowRedeploy() {
        return allowRedeploy;
    }

    public void setAllowRedeploy(boolean allowRedeploy) {
        this.allowRedeploy = allowRedeploy;
    }

    public VersionPolicy getVersionPolicy() {
        return versionPolicy;
    }

    public void setVersionPolicy(VersionPolicy versionPolicy) {
        this.versionPolicy = versionPolicy;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public void setRemoteUrl(String remoteUrl) {
        this.remoteUrl = remoteUrl;
    }

    public String getRemoteUsername() {
        return remoteUsername;
    }

    public void setRemoteUsername(String remoteUsername) {
        this.remoteUsername = remoteUsername;
    }

    public String getRemotePassword() {
        return remotePassword;
    }

    public void setRemotePassword(String remotePassword) {
        this.remotePassword = remotePassword;
    }

    public String getRemoteToken() {
        return remoteToken;
    }

    public void setRemoteToken(String remoteToken) {
        this.remoteToken = remoteToken;
    }

    public Integer getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(Integer connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public Integer getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(Integer readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public Integer getMetadataMaxAgeMinutes() {
        return metadataMaxAgeMinutes;
    }

    public void setMetadataMaxAgeMinutes(Integer metadataMaxAgeMinutes) {
        this.metadataMaxAgeMinutes = metadataMaxAgeMinutes;
    }

    public Integer getNegativeCacheMinutes() {
        return negativeCacheMinutes;
    }

    public void setNegativeCacheMinutes(Integer negativeCacheMinutes) {
        this.negativeCacheMinutes = negativeCacheMinutes;
    }

    public boolean isOfflineMode() {
        return offlineMode;
    }

    public void setOfflineMode(boolean offlineMode) {
        this.offlineMode = offlineMode;
    }

    public List<String> getMembers() {
        return members;
    }

    public void setMembers(List<String> members) {
        this.members = members == null ? new ArrayList<>() : new ArrayList<>(members);
    }

    public Map<String, String> getFormatSettings() {
        return formatSettings;
    }

    public void setFormatSettings(Map<String, String> formatSettings) {
        this.formatSettings = formatSettings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(formatSettings);
    }

    /** A format setting, if set and not blank. */
    public Optional<String> setting(String key) {
        String value = formatSettings == null ? null : formatSettings.get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    public String setting(String key, String defaultValue) {
        return setting(key).orElse(defaultValue);
    }

    public boolean settingEnabled(String key, boolean defaultValue) {
        return setting(key).map(v -> v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"))
                .orElse(defaultValue);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "Repository{" + name + ", " + format + ", " + type + "}";
    }
}
