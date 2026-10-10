package dev.granary.format.go;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The syntax rules of module paths, versions and file paths as the go command enforces them
 * ({@code golang.org/x/mod/module}), plus the case encoding of the GOPROXY protocol: an upper-case letter
 * {@code X} travels as {@code !x} so that case-insensitive file systems and proxies never confuse two modules.
 */
public final class GoModulePaths {

    static final long MAX_ZIP_FILE = 500L << 20;
    static final long MAX_GO_MOD = 16L << 20;
    static final long MAX_LICENSE = 16L << 20;

    private static final List<String> BAD_WINDOWS_NAMES = List.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private static final String FILE_NAME_PUNCTUATION = "!#$%&()+,-.=@[]^_{}~ ";

    private GoModulePaths() {
    }

    // --- case encoding ---------------------------------------------------------------------------------

    /** {@code module.EscapePath}/{@code EscapeVersion}: every upper-case ASCII letter becomes {@code !} plus its lower-case form. */
    public static String escape(String plain) {
        StringBuilder sb = new StringBuilder(plain.length() + 4);
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (c == '!') {
                throw GoError.invalidPath("invalid module path \"" + plain + "\": internal ! in path");
            }
            if (c >= 'A' && c <= 'Z') {
                sb.append('!').append((char) (c + ('a' - 'A')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * {@code module.UnescapePath}/{@code UnescapeVersion}: {@code !x} becomes {@code X}. A {@code !} followed by
     * anything but a lower-case letter, or a literal upper-case letter, is refused the way the go command refuses it.
     */
    public static String unescape(String escaped) {
        StringBuilder sb = new StringBuilder(escaped.length());
        boolean bang = false;
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c >= 0x80) {
                throw GoError.invalidPath("invalid escaped module path \"" + escaped + "\"");
            }
            if (bang) {
                bang = false;
                if (c < 'a' || c > 'z') {
                    throw GoError.invalidPath("invalid escaped module path \"" + escaped + "\"");
                }
                sb.append((char) (c - ('a' - 'A')));
            } else if (c == '!') {
                bang = true;
            } else if (c >= 'A' && c <= 'Z') {
                throw GoError.invalidPath("invalid escaped module path \"" + escaped + "\": upper-case letters must be written as !x");
            } else {
                sb.append(c);
            }
        }
        if (bang) {
            throw GoError.invalidPath("invalid escaped module path \"" + escaped + "\"");
        }
        return sb.toString();
    }

    // --- module paths ----------------------------------------------------------------------------------

    /** {@code module.CheckPath}: a module path the go command would accept, e.g. {@code github.com/BurntSushi/toml/v2}. */
    public static void checkModulePath(String path) {
        checkPath(path, true);
        int slash = path.indexOf('/');
        String first = slash < 0 ? path : path.substring(0, slash);
        if (first.indexOf('.') < 0) {
            throw GoError.invalidPath("malformed module path \"" + path + "\": missing dot in first path element");
        }
        if (path.charAt(0) == '-') {
            throw GoError.invalidPath("malformed module path \"" + path + "\": leading dash in first path element");
        }
        for (int i = 0; i < first.length(); i++) {
            char c = first.charAt(i);
            boolean ok = c == '-' || c == '.' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z');
            if (!ok) {
                throw GoError.invalidPath("malformed module path \"" + path + "\": invalid char '" + c + "' in first path element");
            }
        }
        if (splitPathVersion(path).isEmpty()) {
            throw GoError.invalidPath("malformed module path \"" + path + "\": invalid version");
        }
    }

    /** {@code module.CheckFilePath}: a slash-separated path inside a module zip, relative to the module root. */
    public static void checkFilePath(String path) {
        checkPath(path, false);
    }

    private static void checkPath(String path, boolean modulePath) {
        String what = modulePath ? "module path" : "file path";
        if (path == null || path.isEmpty()) {
            throw GoError.invalidPath("malformed " + what + " \"\": empty string");
        }
        if (modulePath && path.charAt(0) == '-') {
            throw GoError.invalidPath("malformed " + what + " \"" + path + "\": leading dash");
        }
        if (path.contains("//")) {
            throw GoError.invalidPath("malformed " + what + " \"" + path + "\": double slash");
        }
        if (path.charAt(path.length() - 1) == '/') {
            throw GoError.invalidPath("malformed " + what + " \"" + path + "\": trailing slash");
        }
        for (String elem : path.split("/", -1)) {
            String problem = checkElem(elem, modulePath);
            if (problem != null) {
                throw GoError.invalidPath("malformed " + what + " \"" + path + "\": " + problem);
            }
        }
    }

    private static String checkElem(String elem, boolean modulePath) {
        if (elem.isEmpty()) {
            return "empty path element";
        }
        if (elem.chars().allMatch(c -> c == '.')) {
            return "invalid path element \"" + elem + "\"";
        }
        if (modulePath && elem.charAt(0) == '.') {
            return "leading dot in path element";
        }
        if (elem.charAt(elem.length() - 1) == '.') {
            return "trailing dot in path element";
        }
        for (int i = 0; i < elem.length(); ) {
            int cp = elem.codePointAt(i);
            boolean ok = modulePath ? modPathOk(cp) : fileNameOk(cp);
            if (!ok) {
                return "invalid char '" + new String(Character.toChars(cp)) + "'";
            }
            i += Character.charCount(cp);
        }
        String shortName = elem;
        int dot = shortName.indexOf('.');
        if (dot >= 0) {
            shortName = shortName.substring(0, dot);
        }
        for (String bad : BAD_WINDOWS_NAMES) {
            if (bad.equalsIgnoreCase(shortName)) {
                return "\"" + shortName + "\" disallowed as path element component on Windows";
            }
        }
        if (!modulePath) {
            return null;
        }
        int tilde = shortName.lastIndexOf('~');
        if (tilde >= 0 && tilde < shortName.length() - 1) {
            String suffix = shortName.substring(tilde + 1);
            if (suffix.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return "trailing tilde and digits in path element";
            }
        }
        return null;
    }

    private static boolean modPathOk(int c) {
        return c < 0x80 && (c == '-' || c == '.' || c == '_' || c == '~'
                || (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'));
    }

    private static boolean fileNameOk(int c) {
        if (c < 0x80) {
            return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || FILE_NAME_PUNCTUATION.indexOf(c) >= 0;
        }
        return Character.isLetter(c) || Character.isDigit(c);
    }

    // --- major version suffixes ------------------------------------------------------------------------

    /**
     * {@code module.SplitPathVersion}: the major-version suffix of a module path ({@code /v2}, or {@code .v2} for
     * gopkg.in), empty when there is none; {@link Optional#empty()} when the suffix is malformed ({@code /v1}, {@code /v02}).
     */
    public static Optional<String> splitPathVersion(String path) {
        if (path.startsWith("gopkg.in/")) {
            return splitGopkgIn(path);
        }
        int i = path.length();
        boolean dot = false;
        while (i > 0 && ((path.charAt(i - 1) >= '0' && path.charAt(i - 1) <= '9') || path.charAt(i - 1) == '.')) {
            if (path.charAt(i - 1) == '.') {
                dot = true;
            }
            i--;
        }
        if (i <= 1 || i == path.length() || path.charAt(i - 1) != 'v' || path.charAt(i - 2) != '/') {
            return Optional.of("");
        }
        String pathMajor = path.substring(i - 2);
        if (dot || pathMajor.length() <= 2 || pathMajor.charAt(2) == '0' || pathMajor.equals("/v1")) {
            return Optional.empty();
        }
        return Optional.of(pathMajor);
    }

    private static Optional<String> splitGopkgIn(String path) {
        int i = path.length();
        if (path.endsWith("-unstable")) {
            i -= "-unstable".length();
        }
        while (i > 0 && path.charAt(i - 1) >= '0' && path.charAt(i - 1) <= '9') {
            i--;
        }
        if (i <= 1 || path.charAt(i - 1) != 'v' || path.charAt(i - 2) != '.') {
            return Optional.empty();
        }
        String pathMajor = path.substring(i - 2);
        if (pathMajor.length() <= 2 || (pathMajor.charAt(2) == '0' && !pathMajor.equals(".v0"))) {
            return Optional.empty();
        }
        return Optional.of(pathMajor);
    }

    /**
     * {@code module.Check}: the version must be canonical semver and its major version must agree with the
     * path's suffix ({@code v2.1.0} needs a {@code /v2} path unless it is {@code +incompatible}).
     */
    public static void checkPathMajor(String path, String version) {
        if (!GoSemver.isValid(version)) {
            throw GoError.invalidVersion("malformed version \"" + version + "\": must be a semantic version starting with v");
        }
        if (!GoSemver.canonical(version).equals(version)) {
            throw GoError.invalidVersion("non-canonical version \"" + version + "\": should be " + GoSemver.canonical(version));
        }
        String pathMajor = splitPathVersion(path).orElse("");
        if (pathMajor.startsWith(".v") && pathMajor.endsWith("-unstable")) {
            pathMajor = pathMajor.substring(0, pathMajor.length() - "-unstable".length());
        }
        if (version.startsWith("v0.0.0-") && pathMajor.equals(".v1")) {
            return;
        }
        String major = GoSemver.major(version);
        boolean incompatible = "+incompatible".equals(GoSemver.build(version));
        if (pathMajor.isEmpty()) {
            if (major.equals("v0") || major.equals("v1") || incompatible) {
                return;
            }
            throw GoError.invalidVersion("invalid version \"" + version + "\" for module \"" + path
                    + "\": should be v0 or v1, not " + major + " (a " + major + " module path must end in /" + major + ")");
        }
        if (incompatible) {
            throw GoError.invalidVersion("invalid version \"" + version + "\" for module \"" + path
                    + "\": +incompatible suffix not allowed: module path includes a major version suffix");
        }
        if (major.equals(pathMajor.substring(1))) {
            return;
        }
        throw GoError.invalidVersion("invalid version \"" + version + "\" for module \"" + path
                + "\": should be " + pathMajor.substring(1) + ", not " + major);
    }

    // --- component coordinates -------------------------------------------------------------------------

    /** Everything before the last path element ({@code github.com/BurntSushi} for {@code github.com/BurntSushi/toml}), or empty. */
    public static String namespace(String modulePath) {
        int slash = modulePath.lastIndexOf('/');
        return slash < 0 ? "" : modulePath.substring(0, slash);
    }

    /** The last path element. */
    public static String name(String modulePath) {
        int slash = modulePath.lastIndexOf('/');
        return slash < 0 ? modulePath : modulePath.substring(slash + 1);
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
