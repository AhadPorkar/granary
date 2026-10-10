package dev.granary.format.raw;

import dev.granary.core.storage.StorageService;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How browser CDNs encode versions in URLs. jsDelivr ({@code npm/<pkg>@1.2.3/file}, {@code gh/<user>/<repo>@v1.2.3/file})
 * and unpkg ({@code <pkg>@1.2.3/file}) pin with {@code @}; cdnjs puts the version in a directory
 * ({@code <library>/1.2.3/file}). Everything that pins an exact version is immutable; ranges
 * ({@code @^1}, {@code @1}, {@code @latest}) and unversioned paths resolve to something else over time.
 */
final class CdnPaths {

    private static final Pattern EXACT_SEMVER = Pattern.compile("^v?\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.+-]*)?$");
    private static final Pattern VERSION_DIRECTORY = Pattern.compile("^v?\\d+(?:\\.\\d+)+(?:[-+.][0-9A-Za-z.+-]*)?$");
    private static final Set<String> NAMESPACES = Set.of("npm", "gh", "wp");

    private CdnPaths() {
    }

    /** Whether the path pins an exact version and may therefore be cached forever. */
    static boolean isPinned(String path) {
        List<String> segments = RawPaths.segments(path);
        for (String segment : segments) {
            int at = segment.lastIndexOf('@');
            if (at > 0 && EXACT_SEMVER.matcher(segment.substring(at + 1)).matches()) {
                return true;
            }
        }
        return segments.size() >= 3 && !segments.get(0).contains("@")
                && VERSION_DIRECTORY.matcher(segments.get(1)).matches();
    }

    /** The package a pinned path belongs to; unpinned paths get the generic directory/file coordinates. */
    static StorageService.ComponentCoordinates coordinates(String path) {
        List<String> segments = RawPaths.segments(path);
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            int at = segment.lastIndexOf('@');
            if (at > 0 && EXACT_SEMVER.matcher(segment.substring(at + 1)).matches()) {
                String version = segment.substring(at + 1);
                String namespace = i > 0 && NAMESPACES.contains(segments.get(0)) ? segments.get(0) : "";
                int from = namespace.isEmpty() ? 0 : 1;
                StringBuilder name = new StringBuilder();
                for (int j = from; j < i; j++) {
                    name.append(segments.get(j)).append('/');
                }
                name.append(segment, 0, at);
                return new StorageService.ComponentCoordinates(namespace, name.toString(), version);
            }
        }
        if (segments.size() >= 3 && !segments.get(0).contains("@") && VERSION_DIRECTORY.matcher(segments.get(1)).matches()) {
            return new StorageService.ComponentCoordinates("", segments.get(0), segments.get(1));
        }
        return RawComponents.generic(path);
    }
}
