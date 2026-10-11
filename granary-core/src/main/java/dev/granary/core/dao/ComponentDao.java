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

    /**
     * Components whose name or namespace contains {@code term}, ignoring case, in one repository or (with a null
     * {@code repositoryId}) in all of them. The term is matched literally: {@code %}, {@code _} and {@code \} are
     * escaped, with the escape character named in the query so H2 and PostgreSQL agree. A term with a NUL character
     * matches nothing (no coordinates contain one) and never reaches the database, which on PostgreSQL would refuse it.
     */
    default Page<Component> search(UUID repositoryId, String term, Pageable pageable) {
        if (term == null || term.indexOf('\0') >= 0) {
            return Page.empty(pageable);
        }
        String literal = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return searchLiteral(repositoryId, literal, pageable);
    }

    /** {@link #search} with a term whose LIKE wildcards are already escaped with a backslash. */
    @Query("select c from Component c where (:repositoryId is null or c.repositoryId = :repositoryId) "
            + "and (lower(c.name) like lower(concat('%', :term, '%')) escape '\\' "
            + "or lower(c.namespace) like lower(concat('%', :term, '%')) escape '\\') "
            + "order by c.namespace, c.name, c.version")
    Page<Component> searchLiteral(@Param("repositoryId") UUID repositoryId, @Param("term") String term,
                                  Pageable pageable);

    void deleteByRepositoryId(UUID repositoryId);
}
