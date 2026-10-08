package dev.granary.core.dao;

import dev.granary.core.model.Component;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComponentDao extends JpaRepository<Component, UUID> {

    Optional<Component> findByRepositoryIdAndNamespaceAndNameAndVersion(
            UUID repositoryId, String namespace, String name, String version);

    List<Component> findByRepositoryIdAndNamespaceAndNameOrderByVersionAsc(
            UUID repositoryId, String namespace, String name);

    Page<Component> findByRepositoryIdOrderByNamespaceAscNameAscVersionAsc(UUID repositoryId, Pageable pageable);

    long countByRepositoryId(UUID repositoryId);

    @Query("select c from Component c where (:repositoryId is null or c.repositoryId = :repositoryId) "
            + "and (lower(c.name) like lower(concat('%', :term, '%')) "
            + "or lower(c.namespace) like lower(concat('%', :term, '%'))) "
            + "order by c.namespace, c.name, c.version")
    Page<Component> search(@Param("repositoryId") UUID repositoryId, @Param("term") String term, Pageable pageable);

    void deleteByRepositoryId(UUID repositoryId);
}
