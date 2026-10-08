package dev.granary.core.repository;

import dev.granary.core.format.FormatRegistry;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.model.VersionPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The conventional proxy / hosted / group trio for every format Granary knows, so a fresh server is
 * usable with one click. Only repositories whose name is still free are created; existing ones are
 * left untouched, which makes the operation safe to repeat.
 */
@Service
public class StandardRepositories {

    public record Template(String name, String format, RepositoryType type, String remoteUrl,
                           VersionPolicy versionPolicy, List<String> members, String description) {
    }

    private static final Map<String, List<Template>> BY_FORMAT = new LinkedHashMap<>();

    static {
        BY_FORMAT.put("maven2", List.of(
                new Template("maven-central", "maven2", RepositoryType.PROXY, "https://repo1.maven.org/maven2/",
                        VersionPolicy.RELEASE, List.of(), "Proxy of Maven Central"),
                new Template("maven-releases", "maven2", RepositoryType.HOSTED, null,
                        VersionPolicy.RELEASE, List.of(), "Your released artifacts"),
                new Template("maven-snapshots", "maven2", RepositoryType.HOSTED, null,
                        VersionPolicy.SNAPSHOT, List.of(), "Your snapshot builds"),
                new Template("maven-public", "maven2", RepositoryType.GROUP, null, VersionPolicy.MIXED,
                        List.of("maven-releases", "maven-snapshots", "maven-central"),
                        "Single URL for Maven and Gradle clients")));
        BY_FORMAT.put("npm", List.of(
                new Template("npm-proxy", "npm", RepositoryType.PROXY, "https://registry.npmjs.org/",
                        null, List.of(), "Proxy of the public npm registry"),
                new Template("npm-hosted", "npm", RepositoryType.HOSTED, null, null, List.of(), "Your own packages"),
                new Template("npm-group", "npm", RepositoryType.GROUP, null, null,
                        List.of("npm-hosted", "npm-proxy"), "Single registry URL for npm, pnpm and yarn")));
        BY_FORMAT.put("nuget", List.of(
                new Template("nuget.org", "nuget", RepositoryType.PROXY, "https://api.nuget.org/v3/index.json",
                        null, List.of(), "Proxy of nuget.org"),
                new Template("nuget-hosted", "nuget", RepositoryType.HOSTED, null, null, List.of(), "Your own packages"),
                new Template("nuget-group", "nuget", RepositoryType.GROUP, null, null,
                        List.of("nuget-hosted", "nuget.org"), "Single package source for dotnet and Visual Studio")));
    }

    private final RepositoryService repositories;
    private final FormatRegistry formats;

    public StandardRepositories(RepositoryService repositories, FormatRegistry formats) {
        this.repositories = repositories;
        this.formats = formats;
    }

    /** Templates for the formats that are actually installed, in creation order (members before groups). */
    public List<Template> templates() {
        List<Template> result = new ArrayList<>();
        BY_FORMAT.forEach((format, templates) -> {
            if (formats.find(format).isPresent()) {
                result.addAll(templates);
            }
        });
        return result;
    }

    /** Creates every missing standard repository and returns the names that were created. */
    public List<String> createMissing() {
        List<String> created = new ArrayList<>();
        for (Template t : templates()) {
            if (repositories.find(t.name()).isPresent()) {
                continue;
            }
            Repository repo = new Repository();
            repo.setName(t.name());
            repo.setFormat(t.format());
            repo.setType(t.type());
            repo.setRemoteUrl(t.remoteUrl());
            repo.setVersionPolicy(t.versionPolicy());
            repo.setMembers(new ArrayList<>(t.members()));
            repo.setDescription(t.description());
            repositories.create(repo);
            created.add(t.name());
        }
        return created;
    }
}
