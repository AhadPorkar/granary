package dev.granary.core.repository;

import dev.granary.core.dao.RepositoryDao;
import dev.granary.core.model.Repository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Expands a group into its ordered list of non-group member repositories, nested groups included. */
@Component
public class GroupResolver {

    private final RepositoryDao repositories;

    public GroupResolver(RepositoryDao repositories) {
        this.repositories = repositories;
    }

    @Transactional(readOnly = true)
    public List<Repository> leafMembers(Repository group) {
        List<Repository> result = new ArrayList<>();
        collect(group, result, new HashSet<>());
        return result;
    }

    private void collect(Repository repo, List<Repository> out, Set<String> visited) {
        if (!visited.add(repo.getName())) {
            return; // the validator forbids cycles, but never trust persisted data blindly
        }
        if (!repo.isGroup()) {
            out.add(repo);
            return;
        }
        for (String memberName : repo.getMembers()) {
            repositories.findByName(memberName)
                    .filter(Repository::isOnline)
                    .ifPresent(member -> collect(member, out, visited));
        }
    }
}
