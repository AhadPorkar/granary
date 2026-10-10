package dev.granary.format.raw;

import dev.granary.core.GranaryException;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The three repository settings every raw profile shares. */
public final class RawSettings {

    /** Comma-separated globs of paths that are mutable metadata rather than immutable files. */
    public static final String METADATA_PATTERNS = "raw.metadataPatterns";
    /** {@code inline} (default) or {@code attachment}: how browsers are told to treat downloads. */
    public static final String CONTENT_DISPOSITION = "raw.contentDisposition";
    /** Whether directory paths answer with an index page. */
    public static final String LISTING_ENABLED = "raw.listingEnabled";

    public static final String INLINE = "inline";
    public static final String ATTACHMENT = "attachment";

    /** Compiled pattern lists keyed by their source text; the number of distinct values is tiny in practice. */
    private static final Map<String, GlobMatcher> COMPILED = new ConcurrentHashMap<>();
    private static final int COMPILED_LIMIT = 256;

    private RawSettings() {
    }

    public static List<SettingDescriptor> descriptors(String defaultPatterns) {
        return List.of(
                SettingDescriptor.string(METADATA_PATTERNS, "Metadata path patterns", defaultPatterns,
                        "Comma-separated globs (* within a segment, ** across segments) of paths that are mutable index "
                                + "documents: re-validated upstream after the metadata max age instead of cached forever."),
                SettingDescriptor.string(CONTENT_DISPOSITION, "Content disposition", INLINE,
                        "'inline' lets browsers display files, 'attachment' makes them download with the file name."),
                SettingDescriptor.bool(LISTING_ENABLED, "Directory listings", true,
                        "Answer paths ending with a slash with an index page (HTML, or JSON for Accept: application/json)."));
    }

    /** The repository's own pattern list when set, otherwise the profile default. */
    public static GlobMatcher metadataPatterns(Repository repo, GlobMatcher defaults) {
        return repo.setting(METADATA_PATTERNS).map(RawSettings::compile).orElse(defaults);
    }

    public static boolean attachment(Repository repo) {
        return ATTACHMENT.equalsIgnoreCase(repo.setting(CONTENT_DISPOSITION, INLINE));
    }

    public static boolean listingEnabled(Repository repo) {
        return repo.settingEnabled(LISTING_ENABLED, true);
    }

    public static void validate(Repository repo) {
        String disposition = repo.setting(CONTENT_DISPOSITION, INLINE).toLowerCase(Locale.ROOT);
        if (!disposition.equals(INLINE) && !disposition.equals(ATTACHMENT)) {
            throw GranaryException.badRequest("repository.raw.contentDisposition",
                    CONTENT_DISPOSITION + " must be 'inline' or 'attachment'");
        }
        repo.setting(METADATA_PATTERNS).ifPresent(patterns -> {
            for (String glob : patterns.split(",")) {
                String g = glob.trim();
                if (g.isEmpty()) {
                    continue;
                }
                for (int i = 0; i < g.length(); i++) {
                    char c = g.charAt(i);
                    if (c == '\\' || c < 0x20 || c == 0x7f) {
                        throw GranaryException.badRequest("repository.raw.metadataPatterns",
                                METADATA_PATTERNS + " contains an illegal character in '" + g + "'");
                    }
                }
            }
        });
        repo.setting(LISTING_ENABLED).ifPresent(value -> {
            String v = value.toLowerCase(Locale.ROOT);
            if (!List.of("true", "false", "1", "0", "yes", "no").contains(v)) {
                throw GranaryException.badRequest("repository.raw.listingEnabled",
                        LISTING_ENABLED + " must be true or false");
            }
        });
    }

    private static GlobMatcher compile(String patterns) {
        GlobMatcher cached = COMPILED.get(patterns);
        if (cached != null) {
            return cached;
        }
        if (COMPILED.size() >= COMPILED_LIMIT) {
            COMPILED.clear();
        }
        return COMPILED.computeIfAbsent(patterns, GlobMatcher::of);
    }
}
