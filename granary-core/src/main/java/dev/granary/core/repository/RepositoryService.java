package dev.granary.core.repository;

import dev.granary.core.GranaryException;
import dev.granary.core.dao.AssetDao;
import dev.granary.core.dao.ComponentDao;
import dev.granary.core.dao.RepositoryDao;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD for repository definitions plus the invariants that span several repositories. */
@Service
public class RepositoryService {

    private final RepositoryDao repositories;
    private final AssetDao assets;
    private final ComponentDao components;
    private final RepositoryValidator validator;

    public RepositoryService(RepositoryDao repositories, AssetDao assets, ComponentDao components,
                             RepositoryValidator validator) {
        this.repositories = repositories;
        this.assets = assets;
        this.components = components;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public List<Repository> list() {
        return repositories.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Optional<Repository> find(String name) {
        return repositories.findByName(name);
    }

    @Transactional(readOnly = true)
    public Repository require(String name) {
        return repositories.findByName(name).orElseThrow(() ->
                GranaryException.notFound("repository.notFound", "Repository '" + name + "' does not exist"));
    }

    @Transactional(readOnly = true)
    public Repository require(UUID id) {
        return repositories.findById(id).orElseThrow(() ->
                GranaryException.notFound("repository.notFound", "Repository " + id + " does not exist"));
    }

    @Transactional
    public Repository create(Repository repo) {
        if (repo.getBlobStore() == null || repo.getBlobStore().isBlank()) {
            repo.setBlobStore("default");
        }
        validator.validate(repo, true);
        return repositories.save(repo);
    }

    /**
     * Applies the editable fields of {@code changes} onto the stored repository. Name, format and type are
     * immutable after creation because paths, caches and group members depend on them.
     */
    @Transactional
    public Repository update(String name, Repository changes) {
        Repository existing = require(name);
        if (changes.getFormat() != null && !changes.getFormat().equals(existing.getFormat())) {
            throw GranaryException.badRequest("repository.format.immutable", "The format of a repository cannot change");
        }
        if (changes.getType() != null && changes.getType() != existing.getType()) {
            throw GranaryException.badRequest("repository.type.immutable", "The type of a repository cannot change");
        }
        existing.setOnline(changes.isOnline());
        existing.setDescription(changes.getDescription());
        if (changes.getBlobStore() != null && !changes.getBlobStore().isBlank()) {
            existing.setBlobStore(changes.getBlobStore());
        }
        existing.setAllowRedeploy(changes.isAllowRedeploy());
        if (changes.getVersionPolicy() != null) {
            existing.setVersionPolicy(changes.getVersionPolicy());
        }
        existing.setRemoteUrl(changes.getRemoteUrl());
        existing.setRemoteUsername(changes.getRemoteUsername());
        // A blank password in an update means "keep the current one" so the UI never has to echo secrets.
        if (changes.getRemotePassword() != null && !changes.getRemotePassword().isEmpty()) {
            existing.setRemotePassword(changes.getRemotePassword());
        } else if (changes.getRemoteUsername() == null || changes.getRemoteUsername().isBlank()) {
            existing.setRemotePassword(null);
        }
        if (changes.getRemoteToken() != null && !changes.getRemoteToken().isEmpty()) {
            existing.setRemoteToken(changes.getRemoteToken());
        }
        existing.setConnectTimeoutSeconds(changes.getConnectTimeoutSeconds());
        existing.setReadTimeoutSeconds(changes.getReadTimeoutSeconds());
        existing.setMetadataMaxAgeMinutes(changes.getMetadataMaxAgeMinutes());
        existing.setNegativeCacheMinutes(changes.getNegativeCacheMinutes());
        existing.setOfflineMode(changes.isOfflineMode());
        existing.setMembers(changes.getMembers());
        existing.setFormatSettings(changes.getFormatSettings());
        validator.validate(existing, false);
        return repositories.save(existing);
    }

    @Transactional
    public void delete(String name) {
        Repository repo = require(name);
        List<String> dependants = new ArrayList<>();
        for (Repository other : repositories.findByFormatOrderByNameAsc(repo.getFormat())) {
            if (other.getType() == RepositoryType.GROUP && other.getMembers().contains(name)) {
                dependants.add(other.getName());
            }
        }
        if (!dependants.isEmpty()) {
            throw GranaryException.conflict("repository.inUse",
                    "Repository '" + name + "' is a member of " + dependants);
        }
        assets.deleteByRepositoryId(repo.getId());
        components.deleteByRepositoryId(repo.getId());
        repositories.delete(repo);
    }

    @Transactional
    public Repository setOffline(String name, boolean offline) {
        Repository repo = require(name);
        if (!repo.isProxy()) {
            throw GranaryException.badRequest("repository.notProxy", "Only proxy repositories have an offline mode");
        }
        repo.setOfflineMode(offline);
        return repositories.save(repo);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> statistics(Repository repo) {
        return Map.of(
                "assets", assets.countByRepositoryId(repo.getId()),
                "components", components.countByRepositoryId(repo.getId()),
                "bytes", assets.sumSizeByRepositoryId(repo.getId()));
    }
}
