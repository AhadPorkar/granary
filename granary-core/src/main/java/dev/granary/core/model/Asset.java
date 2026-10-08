package dev.granary.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * One stored file inside a repository, addressed by its repository-relative path. The bytes live in a
 * {@link dev.granary.core.blob.BlobStore} under {@link #getBlobSha256()}.
 */
@Entity
@Table(name = "assets", uniqueConstraints = @UniqueConstraint(
        name = "uk_asset_path", columnNames = {"repository_id", "path"}))
public class Asset {

    /** Where an asset came from. */
    public enum Origin {
        /** Uploaded by a client into a hosted repository. */
        UPLOADED,
        /** Fetched from an upstream registry by a proxy repository. */
        CACHED,
        /** Produced by the server itself (generated index or metadata). */
        GENERATED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "component_id")
    private UUID componentId;

    @Column(nullable = false, length = 2000)
    private String path;

    @Column(name = "blob_sha256", nullable = false, length = 64)
    private String blobSha256;

    @Column(nullable = false)
    private long size;

    @Column(name = "content_type", length = 200)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Origin origin = Origin.UPLOADED;

    /** Whether this asset is a mutable index/metadata document that must be re-validated upstream. */
    @Column(nullable = false)
    private boolean metadata = false;

    @Column(name = "upstream_etag", length = 500)
    private String upstreamEtag;

    @Column(name = "upstream_last_modified", length = 100)
    private String upstreamLastModified;

    /** When the content was last confirmed to match upstream (proxy) or written (hosted). */
    @Column(name = "last_verified_at", nullable = false)
    private Instant lastVerifiedAt;

    @Column(name = "last_downloaded_at")
    private Instant lastDownloadedAt;

    @Column(name = "download_count", nullable = false)
    private long downloadCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (lastVerifiedAt == null) {
            lastVerifiedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(UUID repositoryId) {
        this.repositoryId = repositoryId;
    }

    public UUID getComponentId() {
        return componentId;
    }

    public void setComponentId(UUID componentId) {
        this.componentId = componentId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getBlobSha256() {
        return blobSha256;
    }

    public void setBlobSha256(String blobSha256) {
        this.blobSha256 = blobSha256;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Origin getOrigin() {
        return origin;
    }

    public void setOrigin(Origin origin) {
        this.origin = origin;
    }

    public boolean isMetadata() {
        return metadata;
    }

    public void setMetadata(boolean metadata) {
        this.metadata = metadata;
    }

    public String getUpstreamEtag() {
        return upstreamEtag;
    }

    public void setUpstreamEtag(String upstreamEtag) {
        this.upstreamEtag = upstreamEtag;
    }

    public String getUpstreamLastModified() {
        return upstreamLastModified;
    }

    public void setUpstreamLastModified(String upstreamLastModified) {
        this.upstreamLastModified = upstreamLastModified;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public void setLastVerifiedAt(Instant lastVerifiedAt) {
        this.lastVerifiedAt = lastVerifiedAt;
    }

    public Instant getLastDownloadedAt() {
        return lastDownloadedAt;
    }

    public void setLastDownloadedAt(Instant lastDownloadedAt) {
        this.lastDownloadedAt = lastDownloadedAt;
    }

    public long getDownloadCount() {
        return downloadCount;
    }

    public void setDownloadCount(long downloadCount) {
        this.downloadCount = downloadCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
