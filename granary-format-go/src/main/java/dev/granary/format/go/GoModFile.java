package dev.granary.format.go;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The little of go.mod syntax the server needs: the {@code module} directive, which must name the module a
 * version is published under. Everything else (requires, replaces, the go directive) is the client's business.
 */
public final class GoModFile {

    public static final String CONTENT_TYPE = "text/plain; charset=utf-8";

    private GoModFile() {
    }

    /** The module path declared by the first {@code module} directive, unquoted. */
    public static Optional<String> modulePath(String goMod) {
        if (goMod == null) {
            return Optional.empty();
        }
        for (String rawLine : goMod.split("\\r?\\n")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] fields = line.split("\\s+");
            if (fields.length >= 2 && fields[0].equals("module")) {
                return Optional.of(unquote(fields[1]));
            }
        }
        return Optional.empty();
    }

    public static Optional<String> modulePath(byte[] goMod) {
        return goMod == null ? Optional.empty() : modulePath(new String(goMod, StandardCharsets.UTF_8));
    }

    /** A synthetic go.mod for modules published without one ({@code +incompatible} versions). */
    public static byte[] synthetic(String modulePath) {
        return ("module " + modulePath + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Refuses a go.mod that declares a different module path than {@code expected} (the go command would report
     * "module declares its path as … but was required as …") or declares none at all.
     */
    public static void checkDeclaredPath(byte[] goMod, String expected) {
        if (goMod.length > GoModulePaths.MAX_GO_MOD) {
            throw GoError.invalidZip("go.mod file too large (max size is " + GoModulePaths.MAX_GO_MOD + " bytes)");
        }
        String text = new String(goMod, StandardCharsets.UTF_8);
        if (text.indexOf('\0') >= 0) {
            throw GoError.badRequest("go.mod is not a text file");
        }
        String declared = modulePath(text)
                .orElseThrow(() -> GoError.badRequest("go.mod has no module directive; expected \"module " + expected + "\""));
        if (!declared.equals(expected)) {
            throw GoError.badRequest("go.mod declares its path as \"" + declared + "\" but the version is published as \"" + expected + "\"");
        }
    }

    private static String stripComment(String line) {
        int i = line.indexOf("//");
        return i < 0 ? line : line.substring(0, i);
    }

    private static String unquote(String token) {
        if (token.length() >= 2) {
            char first = token.charAt(0);
            char last = token.charAt(token.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')) {
                return token.substring(1, token.length() - 1);
            }
        }
        return token;
    }
}
