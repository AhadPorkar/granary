package dev.granary.core.dao;

import dev.granary.core.model.Asset;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetDao extends JpaRepository<Asset, UUID> {

    Optional<Asset> findByRepositoryIdAndPath(UUID repositoryId, String path);

    List<Asset> findByRepositoryIdAndPathStartingWithOrderByPathAsc(UUID repositoryId, String prefix);

    Page<Asset> findByRepositoryIdOrderByPathAsc(UUID repositoryId, Pageable pageable);

    List<Asset> findByComponentId(UUID componentId);

    long countByRepositoryId(UUID repositoryId);

    @Query("select coalesce(sum(a.size), 0) from Asset a where a.repositoryId = :repositoryId")
    long sumSizeByRepositoryId(@Param("repositoryId") UUID repositoryId);

    long countByBlobSha256(String blobSha256);

    /**
     * Which of {@code digests} are referenced by an asset of a repository that keeps its blobs in one of
     * {@code blobStores}. Used by blob store compaction, one batch of digests at a time.
     */
    @Query("select distinct a.blobSha256 from Asset a where a.blobSha256 in :digests and a.repositoryId in "
            + "(select r.id from Repository r where r.blobStore in :blobStores)")
    List<String> findReferencedBlobs(@Param("digests") Collection<String> digests,
                                     @Param("blobStores") Collection<String> blobStores);

    /** Whether an asset of a repository using one of {@code blobStores} references {@code blobSha256}. */
    @Query("select count(a) > 0 from Asset a where a.blobSha256 = :blobSha256 and a.repositoryId in "
            + "(select r.id from Repository r where r.blobStore in :blobStores)")
    boolean existsByBlobSha256InBlobStores(@Param("blobSha256") String blobSha256,
                                           @Param("blobStores") Collection<String> blobStores);

    @Modifying
    @Query("update Asset a set a.downloadCount = a.downloadCount + 1, a.lastDownloadedAt = :now where a.id = :id")
    void recordDownload(@Param("id") UUID id, @Param("now") Instant now);

    void deleteByRepositoryId(UUID repositoryId);
}
