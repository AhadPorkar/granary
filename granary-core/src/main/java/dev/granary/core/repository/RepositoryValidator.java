package dev.granary.core.repository;

import dev.granary.core.GranaryException;
import dev.granary.core.blob.BlobStoreRegistry;
import dev.granary.core.dao.RepositoryDao;
import dev.granary.core.format.FormatRegistry;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** All structural rules a repository definition must satisfy before it is saved. */
@Component
public class RepositoryValidator {

    /** Names appear in URLs and on the command line, so keep them to a safe character set. */
    public static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}$");

    private final RepositoryDao repositories;
    private final FormatRegistry formats;
    private final BlobStoreRegistry blobStores;

    public RepositoryValidator(RepositoryDao repositories, FormatRegistry formats, BlobStoreRegistry blobStores) {
        this.repositories = repositories;
        this.formats = formats;
        this.blobStores = blobStores;
    }

    public void validate(Repository repo, boolean isNew) {
        if (repo.getName() == null || !NAME.matcher(repo.getName()).matches()) {
            throw GranaryException.badRequest("repository.name.invalid",
                    "Repository name must match " + NAME.pattern());
        }
        if (isNew && repositories.existsByName(repo.getName())) {
            throw GranaryException.conflict("repository.name.taken",
                    "A repository named '" + repo.getName() + "' already exists");
        }
        if (repo.getFormat() == null || formats.find(repo.getFormat()).isEmpty()) {
            throw GranaryException.badRequest("repository.format.unknown",
                    "Unknown format '" + repo.getFormat() + "'. Known formats: " + formats.names());
        }
        if (repo.getType() == null) {
            throw GranaryException.badRequest("repository.type.missing", "Repository type is required");
        }
        if (repo.getBlobStore() == null || blobStores.find(repo.getBlobStore()).isEmpty()) {
            throw GranaryException.badRequest("repository.blobstore.unknown",
                    "Unknown blob store '" + repo.getBlobStore() + "'");
        }
        switch (repo.getType()) {
            case HOSTED -> validateHosted(repo);
            case PROXY -> validateProxy(repo);
            case GROUP -> validateGroup(repo);
        }
        formats.require(repo.getFormat()).validate(repo);
    }

    private void validateHosted(Repository repo) {
        if (!repo.getMembers().isEmpty()) {
            throw GranaryException.badRequest("repository.members.notAllowed",
                    "Only group repositories have members");
        }
    }

    private void validateProxy(Repository repo) {
        String url = repo.getRemoteUrl();
        if (url == null || url.isBlank()) {
            throw GranaryException.badRequest("repository.remoteUrl.missing",
                    "A proxy repository needs a remote URL");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw GranaryException.badRequest("repository.remoteUrl.invalid", "Remote URL is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            throw GranaryException.badRequest("repository.remoteUrl.invalid",
                    "Remote URL must be an absolute http(s) URL");
        }
        if (!repo.getMembers().isEmpty()) {
            throw GranaryException.badRequest("repository.members.notAllowed",
                    "Only group repositories have members");
        }
        checkPositive(repo.getConnectTimeoutSeconds(), "connectTimeoutSeconds");
        checkPositive(repo.getReadTimeoutSeconds(), "readTimeoutSeconds");
        checkNonNegative(repo.getMetadataMaxAgeMinutes(), "metadataMaxAgeMinutes");
        checkNonNegative(repo.getNegativeCacheMinutes(), "negativeCacheMinutes");
    }

    private void validateGroup(Repository repo) {
        if (repo.getMembers().isEmpty()) {
            throw GranaryException.badRequest("repository.members.empty",
                    "A group repository needs at least one member");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String member : repo.getMembers()) {
            if (!seen.add(member)) {
                throw GranaryException.badRequest("repository.members.duplicate",
                        "Member '" + member + "' is listed twice", Map.of("member", member));
            }
            if (member.equals(repo.getName())) {
                throw GranaryException.badRequest("repository.members.self",
                        "A group cannot contain itself");
            }
            Repository target = repositories.findByName(member).orElseThrow(() ->
                    GranaryException.badRequest("repository.members.unknown",
                            "Member repository '" + member + "' does not exist", Map.of("member", member)));
            if (!target.getFormat().equals(repo.getFormat())) {
                throw GranaryException.badRequest("repository.members.formatMismatch",
                        "Member '" + member + "' has format " + target.getFormat()
                                + " but the group is " + repo.getFormat(), Map.of("member", member));
            }
        }
        if (reachesItself(repo)) {
            throw GranaryException.badRequest("repository.members.cycle",
                    "Group membership would form a cycle");
        }
    }

    /** Depth-first walk through nested groups to make sure {@code repo} is never its own descendant. */
    private boolean reachesItself(Repository repo) {
        Deque<String> stack = new ArrayDeque<>(repo.getMembers());
        Set<String> visited = new HashSet<>();
        while (!stack.isEmpty()) {
            String name = stack.pop();
            if (name.equals(repo.getName())) {
                return true;
            }
            if (!visited.add(name)) {
                continue;
            }
            Optional<Repository> member = repositories.findByName(name);
            if (member.isPresent() && member.get().getType() == RepositoryType.GROUP) {
                stack.addAll(member.get().getMembers());
            }
        }
        return false;
    }

    private static void checkPositive(Integer value, String field) {
        if (value != null && value <= 0) {
            throw GranaryException.badRequest("repository.field.positive",
                    field + " must be greater than zero", Map.of("field", field));
        }
    }

    private static void checkNonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw GranaryException.badRequest("repository.field.nonNegative",
                    field + " must not be negative", Map.of("field", field));
        }
    }
}
