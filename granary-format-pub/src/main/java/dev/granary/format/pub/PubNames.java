package dev.granary.format.pub;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Package name rules. A pub package name is a Dart identifier; pub.dev further insists on lower case and
 * refuses Dart's reserved words, because the name becomes part of every {@code package:} import.
 */
public final class PubNames {

    /** What may appear in a URL: any identifier the pub client itself would ask for. */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    /** What may be published: lower-case identifiers, the rule pub.dev enforces. */
    private static final Pattern PUBLISHABLE = Pattern.compile("^[a-z_][a-z0-9_]*$");

    static final int MAX_LENGTH = 64;

    /** The reserved words pub refuses as package names (the pub client's own list). */
    static final Set<String> RESERVED = Set.of(
            "abstract", "as", "assert", "async", "await", "break", "case", "catch", "class", "const", "continue",
            "covariant", "default", "deferred", "do", "dynamic", "else", "enum", "export", "extends", "extension",
            "external", "factory", "false", "final", "finally", "for", "get", "hide", "if", "implements", "import",
            "in", "interface", "is", "late", "library", "mixin", "new", "null", "of", "on", "operator", "part",
            "required", "rethrow", "return", "set", "show", "static", "super", "switch", "sync", "this", "throw",
            "true", "try", "typedef", "var", "void", "while", "with", "yield");

    private PubNames() {
    }

    /** Whether {@code name} can be looked up at all. */
    public static boolean isValid(String name) {
        return name != null && name.length() <= MAX_LENGTH && IDENTIFIER.matcher(name).matches();
    }

    /** Why {@code name} cannot be published, or empty when it can. */
    public static Optional<String> publishProblem(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.of("The pubspec has no package name");
        }
        if (name.length() > MAX_LENGTH) {
            return Optional.of("Package name '" + name + "' is longer than " + MAX_LENGTH + " characters");
        }
        if (!PUBLISHABLE.matcher(name).matches()) {
            return Optional.of("Package name '" + name + "' must consist of lower-case letters, digits and "
                    + "underscores and must not start with a digit");
        }
        if (RESERVED.contains(name)) {
            return Optional.of("Package name '" + name + "' is a reserved word in Dart");
        }
        return Optional.empty();
    }
}
