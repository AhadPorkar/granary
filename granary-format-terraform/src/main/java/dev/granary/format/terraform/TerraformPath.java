package dev.granary.format.terraform;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classifies a repository-relative request path into the endpoints of the Terraform protocols (service
 * discovery, provider registry, module registry, provider network mirror) plus Granary's own upload and
 * file-download paths. Anything that does not match a known shape is {@link Kind#UNKNOWN}; anything that
 * looks like an attempt to escape the layout ({@code ..}, backslashes, empty or odd segments) is
 * {@link Kind#INVALID} and answered with 400 rather than reaching the blob store.
 *
 * <p>Names and versions are checked here once so the handler can trust them: namespaces, provider types,
 * module names and target systems are {@code [A-Za-z0-9_-]}; versions are semantic versions; operating
 * systems and architectures are lower-case {@code [a-z0-9_]}; file names never start with a dot.
 *
 * @param kind      the endpoint
 * @param namespace provider or module namespace
 * @param name      provider type or module name
 * @param system    module target system ({@code aws}, {@code local}, …); {@code null} for providers
 * @param version   the version in canonical form (validated as a semantic version, a leading {@code v} dropped), or {@code null}
 * @param os        provider operating system, or {@code null}
 * @param arch      provider architecture, or {@code null}
 * @param filename  the last path segment of an archive, checksum or signature file, or {@code null}
 * @param hostname  the registry host of a network-mirror path, or {@code null}
 * @param extension archive extension of a module file ({@code tgz}, {@code zip}, …), or {@code null}
 */
public record TerraformPath(Kind kind, String namespace, String name, String system, String version, String os,
                            String arch, String filename, String hostname, String extension) {

    public enum Kind {
        ROOT, DISCOVERY,
        PROVIDER_INFO, PROVIDER_VERSIONS, PROVIDER_VERSION_INFO, PROVIDER_DOWNLOAD, PROVIDER_GPG_KEY,
        PROVIDER_ARCHIVE, PROVIDER_SHASUMS, PROVIDER_SHASUMS_SIG, PROVIDER_FILE,
        MODULE_INFO, MODULE_VERSIONS, MODULE_VERSION_INFO, MODULE_DOWNLOAD, MODULE_ARCHIVE, MODULE_FILE,
        MIRROR_INDEX, MIRROR_VERSION,
        UNKNOWN, INVALID
    }

    public static final String DISCOVERY_PATH = ".well-known/terraform.json";
    public static final String SHASUMS = "SHA256SUMS";
    public static final String SHASUMS_SIG = "SHA256SUMS.sig";

    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$");
    static final Pattern PLATFORM_PART = Pattern.compile("^[a-z0-9_]{1,32}$");
    static final Pattern FILENAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]{0,254}$");
    static final Pattern HOSTNAME = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?(:[0-9]{1,5})?$");

    /** Archive types go-getter recognises by extension; hosted modules are always {@code tgz}. */
    static final Set<String> MODULE_EXTENSIONS = Set.of("tgz", "zip", "tbz2", "txz", "tar");

    private static final TerraformPath ROOT_PATH = of(Kind.ROOT);
    private static final TerraformPath DISCOVERY = of(Kind.DISCOVERY);
    private static final TerraformPath UNKNOWN = of(Kind.UNKNOWN);
    private static final TerraformPath INVALID = of(Kind.INVALID);

    public static TerraformPath parse(String rawPath) {
        if (rawPath == null) {
            return ROOT_PATH;
        }
        String path = rawPath;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return ROOT_PATH;
        }
        if (path.contains("\\") || path.indexOf('\0') >= 0) {
            return INVALID;
        }
        List<String> s = Arrays.asList(path.split("/", -1));
        for (String segment : s) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return INVALID;
            }
        }
        if (path.equals(DISCOVERY_PATH)) {
            return DISCOVERY;
        }
        return switch (s.get(0)) {
            case "v1" -> v1(s);
            case "files" -> files(s);
            case "mirror" -> mirror(s);
            default -> UNKNOWN;
        };
    }

    private static TerraformPath v1(List<String> s) {
        if (s.size() < 4) {
            return UNKNOWN;
        }
        return switch (s.get(1)) {
            case "providers" -> provider(s);
            case "modules" -> module(s);
            default -> UNKNOWN;
        };
    }

    private static TerraformPath provider(List<String> s) {
        String ns = s.get(2);
        String type = s.get(3);
        if (!name(ns) || !name(type)) {
            return INVALID;
        }
        if (s.size() == 4) {
            return new TerraformPath(Kind.PROVIDER_INFO, ns, type, null, null, null, null, null, null, null);
        }
        String third = s.get(4);
        if (s.size() == 5) {
            if (third.equals("versions")) {
                return new TerraformPath(Kind.PROVIDER_VERSIONS, ns, type, null, null, null, null, null, null, null);
            }
            if (third.equals("gpg-key")) {
                return new TerraformPath(Kind.PROVIDER_GPG_KEY, ns, type, null, null, null, null, null, null, null);
            }
            if (!TerraformVersion.isValid(third)) {
                return INVALID;
            }
            return new TerraformPath(Kind.PROVIDER_VERSION_INFO, ns, type, null, canonical(third), null, null, null, null, null);
        }
        if (!TerraformVersion.isValid(third)) {
            return INVALID;
        }
        String version = canonical(third);
        if (s.size() == 6) {
            if (s.get(5).equals(SHASUMS)) {
                return new TerraformPath(Kind.PROVIDER_SHASUMS, ns, type, null, version, null, null, SHASUMS, null, null);
            }
            if (s.get(5).equals(SHASUMS_SIG)) {
                return new TerraformPath(Kind.PROVIDER_SHASUMS_SIG, ns, type, null, version, null, null, SHASUMS_SIG, null, null);
            }
            return UNKNOWN;
        }
        if (s.size() == 8 && s.get(5).equals("download")) {
            if (!platform(s.get(6)) || !platform(s.get(7))) {
                return INVALID;
            }
            return new TerraformPath(Kind.PROVIDER_DOWNLOAD, ns, type, null, version, s.get(6), s.get(7), null, null, null);
        }
        if (s.size() == 8) {
            if (!platform(s.get(5)) || !platform(s.get(6)) || !filename(s.get(7))
                    || !s.get(7).toLowerCase(Locale.ROOT).endsWith(".zip")) {
                return INVALID;
            }
            return new TerraformPath(Kind.PROVIDER_ARCHIVE, ns, type, null, version, s.get(5), s.get(6), s.get(7), null, null);
        }
        return UNKNOWN;
    }

    private static TerraformPath module(List<String> s) {
        if (s.size() < 5) {
            return UNKNOWN;
        }
        String ns = s.get(2);
        String name = s.get(3);
        String system = s.get(4);
        if (!name(ns) || !name(name) || !name(system)) {
            return INVALID;
        }
        if (s.size() == 5) {
            return new TerraformPath(Kind.MODULE_INFO, ns, name, system, null, null, null, null, null, null);
        }
        String fourth = s.get(5);
        if (s.size() == 6) {
            if (fourth.equals("versions")) {
                return new TerraformPath(Kind.MODULE_VERSIONS, ns, name, system, null, null, null, null, null, null);
            }
            if (fourth.equals("download")) {
                return new TerraformPath(Kind.MODULE_DOWNLOAD, ns, name, system, null, null, null, null, null, null);
            }
            if (fourth.endsWith(".tgz")) {
                String version = fourth.substring(0, fourth.length() - 4);
                if (!TerraformVersion.isValid(version)) {
                    return INVALID;
                }
                return new TerraformPath(Kind.MODULE_ARCHIVE, ns, name, system, canonical(version), null, null, fourth, null, "tgz");
            }
            if (!TerraformVersion.isValid(fourth)) {
                return INVALID;
            }
            return new TerraformPath(Kind.MODULE_VERSION_INFO, ns, name, system, canonical(fourth), null, null, null, null, null);
        }
        if (s.size() == 7 && s.get(6).equals("download")) {
            if (!TerraformVersion.isValid(fourth)) {
                return INVALID;
            }
            return new TerraformPath(Kind.MODULE_DOWNLOAD, ns, name, system, canonical(fourth), null, null, null, null, null);
        }
        return UNKNOWN;
    }

    private static TerraformPath files(List<String> s) {
        if (s.size() == 6 && s.get(1).equals("modules")) {
            String ns = s.get(2);
            String name = s.get(3);
            String system = s.get(4);
            String file = s.get(5);
            if (!name(ns) || !name(name) || !name(system) || !filename(file)) {
                return INVALID;
            }
            int dot = file.lastIndexOf('.');
            if (dot <= 0) {
                return INVALID;
            }
            String ext = file.substring(dot + 1).toLowerCase(Locale.ROOT);
            String version = file.substring(0, dot);
            if (!MODULE_EXTENSIONS.contains(ext) || !TerraformVersion.isValid(version)) {
                return INVALID;
            }
            return new TerraformPath(Kind.MODULE_FILE, ns, name, system, canonical(version), null, null, file, null, ext);
        }
        if (s.size() == 5) {
            String ns = s.get(1);
            String type = s.get(2);
            String version = s.get(3);
            String file = s.get(4);
            if (!name(ns) || !name(type) || !TerraformVersion.isValid(version) || !filename(file)) {
                return INVALID;
            }
            return new TerraformPath(Kind.PROVIDER_FILE, ns, type, null, canonical(version), null, null, file, null, null);
        }
        return UNKNOWN;
    }

    private static TerraformPath mirror(List<String> s) {
        if (s.size() != 5) {
            return UNKNOWN;
        }
        String host = s.get(1);
        String ns = s.get(2);
        String type = s.get(3);
        String file = s.get(4);
        if (!HOSTNAME.matcher(host).matches() || !name(ns) || !name(type) || !filename(file)) {
            return INVALID;
        }
        if (file.equals("index.json")) {
            return new TerraformPath(Kind.MIRROR_INDEX, ns, type, null, null, null, null, null, host, null);
        }
        if (file.endsWith(".json")) {
            String version = file.substring(0, file.length() - 5);
            if (!TerraformVersion.isValid(version)) {
                return INVALID;
            }
            return new TerraformPath(Kind.MIRROR_VERSION, ns, type, null, canonical(version), null, null, null, host, null);
        }
        return UNKNOWN;
    }

    /**
     * The one spelling a version is stored and looked up under ({@code v1.0.0} becomes {@code 1.0.0}), so the
     * same release cannot be uploaded twice under two names and every lookup finds it.
     */
    static String canonical(String version) {
        return TerraformVersion.tryParse(version).map(TerraformVersion::toString).orElse(version);
    }

    private static boolean name(String s) {
        return NAME.matcher(s).matches();
    }

    private static boolean platform(String s) {
        return PLATFORM_PART.matcher(s).matches();
    }

    static boolean filename(String s) {
        return FILENAME.matcher(s).matches();
    }

    private static TerraformPath of(Kind kind) {
        return new TerraformPath(kind, null, null, null, null, null, null, null, null, null);
    }

    public boolean isProvider() {
        return switch (kind) {
            case PROVIDER_INFO, PROVIDER_VERSIONS, PROVIDER_VERSION_INFO, PROVIDER_DOWNLOAD, PROVIDER_GPG_KEY,
                 PROVIDER_ARCHIVE, PROVIDER_SHASUMS, PROVIDER_SHASUMS_SIG, PROVIDER_FILE, MIRROR_INDEX, MIRROR_VERSION -> true;
            default -> false;
        };
    }

    /** Documents the handler generates or re-validates upstream, as opposed to immutable archives. */
    public boolean isMetadata() {
        return switch (kind) {
            case ROOT, DISCOVERY, PROVIDER_INFO, PROVIDER_VERSIONS, PROVIDER_VERSION_INFO, PROVIDER_DOWNLOAD,
                 PROVIDER_GPG_KEY, MODULE_INFO, MODULE_VERSIONS, MODULE_VERSION_INFO, MODULE_DOWNLOAD,
                 MIRROR_INDEX, MIRROR_VERSION -> true;
            default -> false;
        };
    }
}
