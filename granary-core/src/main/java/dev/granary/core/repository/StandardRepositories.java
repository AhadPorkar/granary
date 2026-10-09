package dev.granary.core.repository;

import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.FormatRegistry;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.model.Repository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The conventional proxy / hosted / group trio for every format Granary knows, so a fresh server is
 * usable with one click. Each format declares its own templates through {@link FormatHandler#templates()};
 * only repositories whose name is still free are created and existing ones are left untouched, which
 * makes the operation safe to repeat.
 */
@Service
public class StandardRepositories {

    private final RepositoryService repositories;
    private final FormatRegistry formats;

    public StandardRepositories(RepositoryService repositories, FormatRegistry formats) {
        this.repositories = repositories;
        this.formats = formats;
    }

    /** Templates of the installed formats, in creation order (members before groups). */
    public List<RepositoryTemplate> templates() {
        List<RepositoryTemplate> result = new ArrayList<>();
        for (FormatHandler handler : formats.all()) {
            for (RepositoryTemplate template : handler.templates()) {
                if (!handler.format().equals(template.format())) {
                    throw new IllegalStateException("template " + template.name() + " declared by format "
                            + handler.format() + " but targets " + template.format());
                }
                result.add(template);
            }
        }
        return result;
    }

    /** Templates of one format, in creation order. */
    public List<RepositoryTemplate> templates(String format) {
        return templates().stream().filter(t -> t.format().equals(format)).toList();
    }

    /** Creates every missing standard repository and returns the names that were created. */
    public List<String> createMissing() {
        return create(templates());
    }

    /** Creates the missing standard repositories of one format and returns the names that were created. */
    public List<String> createMissing(String format) {
        return create(templates(format));
    }

    private List<String> create(List<RepositoryTemplate> templates) {
        List<String> created = new ArrayList<>();
        for (RepositoryTemplate t : templates) {
            if (repositories.find(t.name()).isPresent()) {
                continue;
            }
            Repository repo = new Repository();
            repo.setName(t.name());
            repo.setFormat(t.format());
            repo.setType(t.type());
            repo.setRemoteUrl(t.remoteUrl());
            if (t.versionPolicy() != null) {
                repo.setVersionPolicy(t.versionPolicy());
            }
            repo.setMembers(new ArrayList<>(t.members()));
            repo.setDescription(t.description());
            repo.setFormatSettings(t.formatSettings());
            repositories.create(repo);
            created.add(t.name());
        }
        return created;
    }
}
