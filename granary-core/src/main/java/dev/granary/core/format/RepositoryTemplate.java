package dev.granary.core.format;

import dev.granary.core.model.RepositoryType;
import dev.granary.core.model.VersionPolicy;
import java.util.List;
import java.util.Map;

/**
 * One of the conventional repositories a format offers out of the box (for example the proxy of the
 * public registry, a hosted repository for your own packages and a group that joins both). Templates
 * are created with {@code POST /api/v1/repositories/standard}; members must be listed before the
 * groups that reference them.
 *
 * @param name           repository name, unique across all formats
 * @param format         format id the template belongs to
 * @param type           hosted, proxy or group
 * @param remoteUrl      upstream URL for proxies, otherwise {@code null}
 * @param versionPolicy  release/snapshot policy where a format has one, otherwise {@code null}
 * @param members        member names for groups, otherwise empty
 * @param description    short description shown in the UI
 * @param formatSettings format-specific settings (see {@link FormatHandler#settings()})
 */
public record RepositoryTemplate(String name, String format, RepositoryType type, String remoteUrl,
                                 VersionPolicy versionPolicy, List<String> members, String description,
                                 Map<String, String> formatSettings) {

    public RepositoryTemplate {
        members = members == null ? List.of() : List.copyOf(members);
        formatSettings = formatSettings == null ? Map.of() : Map.copyOf(formatSettings);
    }

    public static RepositoryTemplate proxy(String name, String format, String remoteUrl, String description) {
        return new RepositoryTemplate(name, format, RepositoryType.PROXY, remoteUrl, null, List.of(), description, Map.of());
    }

    public static RepositoryTemplate hosted(String name, String format, String description) {
        return new RepositoryTemplate(name, format, RepositoryType.HOSTED, null, null, List.of(), description, Map.of());
    }

    public static RepositoryTemplate group(String name, String format, List<String> members, String description) {
        return new RepositoryTemplate(name, format, RepositoryType.GROUP, null, null, members, description, Map.of());
    }

    public RepositoryTemplate withVersionPolicy(VersionPolicy policy) {
        return new RepositoryTemplate(name, format, type, remoteUrl, policy, members, description, formatSettings);
    }

    public RepositoryTemplate withSettings(Map<String, String> settings) {
        return new RepositoryTemplate(name, format, type, remoteUrl, versionPolicy, members, description, settings);
    }
}
