package dev.granary.format.pub;

import dev.granary.core.GranaryException;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** The format-specific repository settings of pub repositories. */
public final class PubSettings {

    /** Largest archive a hosted repository accepts, in MiB. */
    public static final String MAX_ARCHIVE_SIZE = "pub.maxArchiveSizeMb";
    /** Whether a proxy passes the upstream's security advisories through. */
    public static final String ADVISORIES = "pub.advisories";

    /** pub.dev's own limit for a package archive. */
    public static final int DEFAULT_MAX_ARCHIVE_SIZE_MB = 100;
    static final int MAX_ARCHIVE_SIZE_LIMIT_MB = 10 * 1024;

    private static final Set<String> BOOLEANS = Set.of("true", "false", "1", "0", "yes", "no");

    private PubSettings() {
    }

    public static List<SettingDescriptor> descriptors() {
        return List.of(
                SettingDescriptor.integer(MAX_ARCHIVE_SIZE, "Maximum archive size (MiB)", DEFAULT_MAX_ARCHIVE_SIZE_MB,
                                "Largest package archive dart pub publish may upload; pub.dev allows 100 MiB.")
                        .forTypes(RepositoryType.HOSTED),
                SettingDescriptor.bool(ADVISORIES, "Security advisories", true,
                                "Pass the upstream's security advisories through to dart pub. When off, package "
                                        + "listings omit advisoriesUpdated and clients never ask for advisories.")
                        .forTypes(RepositoryType.PROXY));
    }

    public static long maxArchiveBytes(Repository repo) {
        return maxArchiveSizeMb(repo) * 1024L * 1024L;
    }

    static int maxArchiveSizeMb(Repository repo) {
        Optional<String> raw = repo.setting(MAX_ARCHIVE_SIZE);
        if (raw.isEmpty()) {
            return DEFAULT_MAX_ARCHIVE_SIZE_MB;
        }
        try {
            int value = Integer.parseInt(raw.get());
            return value > 0 && value <= MAX_ARCHIVE_SIZE_LIMIT_MB ? value : DEFAULT_MAX_ARCHIVE_SIZE_MB;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_ARCHIVE_SIZE_MB;
        }
    }

    public static boolean advisories(Repository repo) {
        return repo.settingEnabled(ADVISORIES, true);
    }

    /** Refuses values the server could not use. */
    public static void validate(Repository repo) {
        Optional<String> size = repo.setting(MAX_ARCHIVE_SIZE);
        if (size.isPresent()) {
            int value;
            try {
                value = Integer.parseInt(size.get());
            } catch (NumberFormatException e) {
                throw GranaryException.badRequest("repository.pub.maxArchiveSizeMb.invalid",
                        MAX_ARCHIVE_SIZE + " must be a whole number of MiB");
            }
            if (value < 1 || value > MAX_ARCHIVE_SIZE_LIMIT_MB) {
                throw GranaryException.badRequest("repository.pub.maxArchiveSizeMb.invalid",
                        MAX_ARCHIVE_SIZE + " must be between 1 and " + MAX_ARCHIVE_SIZE_LIMIT_MB);
            }
        }
        Optional<String> advisories = repo.setting(ADVISORIES);
        if (advisories.isPresent() && !BOOLEANS.contains(advisories.get().toLowerCase(Locale.ROOT))) {
            throw GranaryException.badRequest("repository.pub.advisories.invalid", ADVISORIES + " must be true or false");
        }
    }
}
