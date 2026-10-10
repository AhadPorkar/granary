package dev.granary.format.swift;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A package identity of the Swift package registry protocol: {@code <scope>.<name>}, for example
 * {@code mona.LinkedList}. Scopes are alphanumeric with single inner hyphens (at most 39 characters), names
 * add single inner underscores (at most 100 characters); both are case-insensitive, so {@link #key()} is the
 * form used for storage paths and comparisons while {@link #id()} keeps the case the publisher used.
 *
 * @param scope the scope as written, e.g. {@code mona}
 * @param name  the package name as written, e.g. {@code LinkedList}
 */
public record SwiftIdentity(String scope, String name) {

    private static final Pattern SCOPE = Pattern.compile("[a-zA-Z0-9](?:[a-zA-Z0-9]|-(?=[a-zA-Z0-9])){0,38}");
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9](?:[a-zA-Z0-9]|[-_](?=[a-zA-Z0-9])){0,99}");

    public SwiftIdentity {
        if (!isValidScope(scope)) {
            throw new IllegalArgumentException("invalid package scope: " + scope);
        }
        if (!isValidName(name)) {
            throw new IllegalArgumentException("invalid package name: " + name);
        }
    }

    public static boolean isValidScope(String scope) {
        return scope != null && SCOPE.matcher(scope).matches();
    }

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Parses {@code scope.name}; empty when either part is invalid or the separator is missing. */
    public static Optional<SwiftIdentity> parse(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String trimmed = id.trim();
        int dot = trimmed.indexOf('.');
        if (dot <= 0 || dot != trimmed.lastIndexOf('.')) {
            return Optional.empty();
        }
        String scope = trimmed.substring(0, dot);
        String name = trimmed.substring(dot + 1);
        return isValidScope(scope) && isValidName(name) ? Optional.of(new SwiftIdentity(scope, name)) : Optional.empty();
    }

    /** {@code scope.name} as written. */
    public String id() {
        return scope + "." + name;
    }

    /** The case-insensitive identity, lower-cased. */
    public String key() {
        return lowerScope() + "." + lowerName();
    }

    public String lowerScope() {
        return scope.toLowerCase(Locale.ROOT);
    }

    public String lowerName() {
        return name.toLowerCase(Locale.ROOT);
    }

    /** Whether both name the same package (identities are case-insensitive). */
    public boolean sameAs(SwiftIdentity other) {
        return other != null && key().equals(other.key());
    }

    @Override
    public String toString() {
        return id();
    }
}
