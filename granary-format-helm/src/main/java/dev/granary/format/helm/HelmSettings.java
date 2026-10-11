package dev.granary.format.helm;

import dev.granary.core.GranaryException;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The format-specific repository settings of Helm repositories. */
final class HelmSettings {

    /** Write chart URLs into {@code index.yaml} relative to the repository URL instead of absolute. */
    static final String RELATIVE_URLS = "helm.relativeUrls";
    /** Largest chart archive (and provenance file) a hosted repository accepts, in MiB; 0 means no limit. */
    static final String MAX_CHART_SIZE_MB = "helm.maxChartSizeMb";
    static final int DEFAULT_MAX_CHART_SIZE_MB = 20;
    private static final int MAX_CHART_SIZE_CEILING_MB = 10 * 1024;

    private HelmSettings() {
    }

    static List<SettingDescriptor> descriptors() {
        return List.of(
                SettingDescriptor.bool(RELATIVE_URLS, "Relative chart URLs", false,
                        "List chart downloads in index.yaml as charts/<file> relative to the repository URL instead of "
                                + "absolute URLs, for servers reached under several host names."),
                SettingDescriptor.integer(MAX_CHART_SIZE_MB, "Maximum chart size (MiB)", DEFAULT_MAX_CHART_SIZE_MB,
                        "Largest chart archive or provenance file accepted by uploads; 0 disables the limit.")
                        .forTypes(RepositoryType.HOSTED));
    }

    static boolean relativeUrls(Repository repo) {
        return repo.settingEnabled(RELATIVE_URLS, false);
    }

    /** The upload limit in bytes, 0 for none. */
    static long maxChartBytes(Repository repo) {
        Optional<String> raw = repo.setting(MAX_CHART_SIZE_MB);
        int mb = DEFAULT_MAX_CHART_SIZE_MB;
        if (raw.isPresent() && !raw.get().isBlank()) {
            try {
                mb = Integer.parseInt(raw.get().trim());
            } catch (NumberFormatException e) {
                mb = DEFAULT_MAX_CHART_SIZE_MB;
            }
        }
        return mb <= 0 ? 0 : mb * 1024L * 1024L;
    }

    static void validate(Repository repo) {
        repo.setting(RELATIVE_URLS).ifPresent(value -> {
            String v = value.trim().toLowerCase(Locale.ROOT);
            if (!List.of("true", "false", "yes", "no", "1", "0").contains(v)) {
                throw GranaryException.badRequest("repository.helm.relativeUrls.invalid",
                        RELATIVE_URLS + " must be true or false");
            }
        });
        repo.setting(MAX_CHART_SIZE_MB).ifPresent(value -> {
            int mb;
            try {
                mb = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw GranaryException.badRequest("repository.helm.maxChartSizeMb.invalid",
                        MAX_CHART_SIZE_MB + " must be a whole number of MiB");
            }
            if (mb < 0 || mb > MAX_CHART_SIZE_CEILING_MB) {
                throw GranaryException.badRequest("repository.helm.maxChartSizeMb.invalid",
                        MAX_CHART_SIZE_MB + " must be between 0 and " + MAX_CHART_SIZE_CEILING_MB);
            }
        });
    }
}
