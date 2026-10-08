package dev.granary.core.dao;

import dev.granary.core.model.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositoryDao extends JpaRepository<Repository, UUID> {

    Optional<Repository> findByName(String name);

    boolean existsByName(String name);

    List<Repository> findAllByOrderByNameAsc();

    List<Repository> findByFormatOrderByNameAsc(String format);
}
