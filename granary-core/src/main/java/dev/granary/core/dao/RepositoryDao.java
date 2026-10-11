package dev.granary.core.dao;

import dev.granary.core.model.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RepositoryDao extends JpaRepository<Repository, UUID> {

    Optional<Repository> findByName(String name);

    boolean existsByName(String name);

    List<Repository> findAllByOrderByNameAsc();

    List<Repository> findByFormatOrderByNameAsc(String format);

    /** Every blob store name some repository uses. */
    @Query("select distinct r.blobStore from Repository r")
    List<String> findBlobStoreNames();
}
