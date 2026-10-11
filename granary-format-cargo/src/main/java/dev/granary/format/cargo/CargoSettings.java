package dev.granary.format.cargo;

import dev.granary.core.GranaryException;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The format-specific repository settings, their defaults and their validation. */
final class CargoSettings {

    static final String AUTH_REQUIRED = "cargo.authRequired";
    static final String DOWNLOAD_URL = "cargo.downloadUrl";
    static final String API_URL = "cargo.apiUrl";

    static final String DEFAULT_DOWNLOAD_URL = "https://static.crates.io/crates/{crate}/{crate}-{version}.crate";
    static final String DEFAULT_API_URL = "https://crates.io";

    private CargoSettings() {
    }

    static List<SettingDescriptor> descriptors() {
        return List.of(
                SettingDescriptor.bool(AUTH_REQUIRED, "Require a token for downloads", false,
                        "Publishes \"auth-required\": true in config.json so cargo sends its token with every index "
                                + "and download request; turn it on when anonymous downloads are disabled."),
                SettingDescriptor.string(DOWNLOAD_URL, "Upstream download URL", DEFAULT_DOWNLOAD_URL,
                        "Where the upstream keeps .crate files, as the \"dl\" value of its config.json (markers "
                                + "{crate}, {version}, {prefix}, {lowerprefix} and {sha256-checksum}; without a marker "
                                + "/{crate}/{version}/download is appended).")
                        .forTypes(RepositoryType.PROXY),
                // a string, not a URL: "none" is a valid value, and the UI only accepts http(s) in URL fields
                SettingDescriptor.string(API_URL, "Upstream web API", DEFAULT_API_URL,
                        "Root of the upstream registry's web API, used to answer cargo search through this proxy, or "
                                + "\"none\" to search the cached crates only; while it is unreachable the search covers "
                                + "the cached crates.")
                        .forTypes(RepositoryType.PROXY));
    }

    static boolean authRequired(Repository repo) {
        return repo.settingEnabled(AUTH_REQUIRED, false);
    }

    static String downloadUrl(Repository repo) {
        return repo.setting(DOWNLOAD_URL, DEFAULT_DOWNLOAD_URL);
    }

    static Optional<String> apiUrl(Repository repo) {
        String value = repo.setting(API_URL, DEFAULT_API_URL);
        return value.equalsIgnoreCase("none") ? Optional.empty() : Optional.of(trimSlash(value));
    }

    static void validate(Repository repo) {
        repo.setting(AUTH_REQUIRED).ifPresent(value -> {
            String v = value.toLowerCase(Locale.ROOT);
            if (!(v.equals("true") || v.equals("false") || v.equals("1") || v.equals("0") || v.equals("yes")
                    || v.equals("no"))) {
                throw GranaryException.badRequest("repository.cargo.authRequired.invalid",
                        AUTH_REQUIRED + " must be true or false");
            }
        });
        repo.setting(DOWNLOAD_URL).ifPresent(value -> {
            String problem = DownloadTemplate.problem(value);
            if (problem != null) {
                throw GranaryException.badRequest("repository.cargo.downloadUrl.invalid", DOWNLOAD_URL + " " + problem);
            }
        });
        repo.setting(API_URL).filter(v -> !v.equalsIgnoreCase("none")).ifPresent(value -> {
            if (!isHttpUrl(value)) {
                throw GranaryException.badRequest("repository.cargo.apiUrl.invalid",
                        API_URL + " must be an absolute http(s) URL (or \"none\" to search the cache only)");
            }
        });
    }

    static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String trimSlash(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
