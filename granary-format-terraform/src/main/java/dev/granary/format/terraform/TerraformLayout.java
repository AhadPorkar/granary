package dev.granary.format.terraform;

import dev.granary.core.proxy.UrlPaths;

/**
 * Where the format keeps things and which URLs it hands out.
 *
 * <p>Hosted repositories store provider releases under {@code providers/<ns>/<type>/<version>/…} (one zip
 * per {@code <os>/<arch>/}, the {@code SHA256SUMS} and its {@code .sig} beside them, the signing key as
 * {@code providers/<ns>/<type>/gpg-key.json}) and modules as {@code modules/<ns>/<name>/<system>/<version>.tgz}.
 * Proxies cache upstream documents under the request path they answer and downloaded archives under the
 * {@code files/…} path clients fetch them from, so a cache hit never needs a second lookup.
 */
final class TerraformLayout {

    static final String PROVIDERS_DIR = "providers/";
    static final String MODULES_DIR = "modules/";
    static final String GPG_KEY_FILE = "gpg-key.json";

    private TerraformLayout() {
    }

    // --- hosted storage ----------------------------------------------------------------------

    static String providerPrefix(String ns, String type) {
        return PROVIDERS_DIR + ns + "/" + type + "/";
    }

    static String providerVersionPrefix(String ns, String type, String version) {
        return providerPrefix(ns, type) + version + "/";
    }

    static String providerArchivePath(String ns, String type, String version, String os, String arch, String filename) {
        return providerVersionPrefix(ns, type, version) + os + "/" + arch + "/" + filename;
    }

    static String shaSumsPath(String ns, String type, String version) {
        return providerVersionPrefix(ns, type, version) + TerraformPath.SHASUMS;
    }

    static String shaSumsSigPath(String ns, String type, String version) {
        return providerVersionPrefix(ns, type, version) + TerraformPath.SHASUMS_SIG;
    }

    static String gpgKeyPath(String ns, String type) {
        return providerPrefix(ns, type) + GPG_KEY_FILE;
    }

    static String modulePrefix(String ns, String name, String system) {
        return MODULES_DIR + ns + "/" + name + "/" + system + "/";
    }

    static String moduleArchivePath(String ns, String name, String system, String version) {
        return modulePrefix(ns, name, system) + version + ".tgz";
    }

    // --- proxy cache paths (identical to the request paths they answer) -------------------------

    static String providerVersionsPath(String ns, String type) {
        return "v1/providers/" + ns + "/" + type + "/versions";
    }

    static String providerInfoPath(String ns, String type, String version) {
        return "v1/providers/" + ns + "/" + type + (version == null ? "" : "/" + version);
    }

    static String providerDownloadPrefix(String ns, String type, String version) {
        return "v1/providers/" + ns + "/" + type + "/" + version + "/download/";
    }

    static String providerDownloadPath(String ns, String type, String version, String os, String arch) {
        return providerDownloadPrefix(ns, type, version) + os + "/" + arch;
    }

    static String providerFilePath(String ns, String type, String version, String filename) {
        return "files/" + ns + "/" + type + "/" + version + "/" + filename;
    }

    static String moduleVersionsPath(String ns, String name, String system) {
        return "v1/modules/" + ns + "/" + name + "/" + system + "/versions";
    }

    static String moduleInfoPath(String ns, String name, String system, String version) {
        return "v1/modules/" + ns + "/" + name + "/" + system + (version == null ? "" : "/" + version);
    }

    static String moduleDownloadPath(String ns, String name, String system, String version) {
        return "v1/modules/" + ns + "/" + name + "/" + system + "/" + version + "/download";
    }

    static String moduleFilePath(String ns, String name, String system, String version, String extension) {
        return "files/modules/" + ns + "/" + name + "/" + system + "/" + version + "." + extension;
    }

    // --- URLs handed to clients -----------------------------------------------------------------

    static String providersBase(String baseUrl) {
        return baseUrl + "/v1/providers/";
    }

    static String modulesBase(String baseUrl) {
        return baseUrl + "/v1/modules/";
    }

    static String providerFileUrl(String baseUrl, String ns, String type, String version, String filename) {
        return baseUrl + "/" + UrlPaths.join("files", ns + "/" + type + "/" + version + "/" + filename);
    }

    static String moduleFileUrl(String baseUrl, String ns, String name, String system, String version, String extension) {
        return baseUrl + "/" + UrlPaths.join("files/modules", ns + "/" + name + "/" + system + "/" + version + "." + extension);
    }

    /** The last path segment of a URL, which for provider release URLs is the file name. */
    static String fileNameOf(String url) {
        if (url == null) {
            return null;
        }
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        int slash = s.lastIndexOf('/');
        String name = slash >= 0 ? s.substring(slash + 1) : s;
        name = java.net.URLDecoder.decode(name.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        return name.isEmpty() ? null : name;
    }
}
