package dev.granary.core.dao;

import dev.granary.core.model.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleDao extends JpaRepository<Role, UUID> {

    Optional<Role> findByName(String name);

    List<Role> findAllByOrderByNameAsc();
}
