package dev.granary.format.conan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Recipe search ({@code GET v2/conans/search?q=…}) the way {@code conan_server} answers it: the pattern uses shell
 * wildcards and matches when it covers any prefix of the reference cut at a {@code /} or {@code @}, so {@code zlib},
 * {@code zlib*}, {@code zlib/1.*} and {@code *} all find {@code zlib/1.3.1}, while {@code lib} does not.
 *
 * <p>Patterns are matched without regular expressions, in at most (reference length × pattern length) steps, and
 * patterns longer than {@link #MAX_PATTERN_LENGTH} characters are refused, so an anonymous search cannot tie up the
 * server however many wildcards it sends.
 */
public final class ConanSearch {

    /** The longest pattern accepted; real references are at most a few hundred characters. */
    public static final int MAX_PATTERN_LENGTH = 512;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ConanSearch() {
    }

    /** Whether {@code pattern} (shell wildcards; empty matches everything) finds {@code reference}. */
    public static boolean matches(String pattern, String reference, boolean ignoreCase) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        Glob glob = compile(canonical(pattern.trim()), ignoreCase);
        for (String prefix : prefixes(canonical(reference))) {
            if (glob.matches(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The partial references a pattern is tried against: {@code zlib}, {@code zlib/}, {@code zlib/1.3.1}, {@code zlib/1.3.1@}, … */
    static List<String> prefixes(String reference) {
        List<String> prefixes = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < reference.length(); i++) {
            char c = reference.charAt(i);
            if (c == '/' || c == '@' || c == '#') {
                if (!token.isEmpty()) {
                    current.append(token);
                    prefixes.add(current.toString());
                    token.setLength(0);
                }
                current.append(c);
                prefixes.add(current.toString());
            } else {
                token.append(c);
            }
        }
        if (!token.isEmpty()) {
            current.append(token);
            prefixes.add(current.toString());
        }
        return prefixes;
    }

    /**
     * Compiles a shell wildcard pattern ({@code *}, {@code ?}, {@code [abc]}, {@code [!a-z]}). An unclosed or empty
     * {@code [} is a literal; a set with a reversed range ({@code [z-a]}) makes the whole pattern literal text.
     */
    static Glob compile(String glob, boolean ignoreCase) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i++);
            switch (c) {
                case '*' -> {
                    if (tokens.isEmpty() || tokens.getLast() != Token.STAR) {
                        tokens.add(Token.STAR);
                    }
                }
                case '?' -> tokens.add(Token.ANY);
                case '[' -> {
                    int close = glob.indexOf(']', i);
                    String set = close < 0 ? "" : glob.substring(i, close);
                    boolean negated = set.startsWith("!") || set.startsWith("^");
                    String members = negated ? set.substring(1) : set;
                    if (members.isEmpty()) {
                        tokens.add(Token.literal('['));
                    } else {
                        Token token = Token.set(members, negated);
                        if (token == null) {
                            return literal(glob, ignoreCase);
                        }
                        tokens.add(token);
                        i = close + 1;
                    }
                }
                default -> tokens.add(Token.literal(c));
            }
        }
        return new Glob(List.copyOf(tokens), ignoreCase);
    }

    private static Glob literal(String text, boolean ignoreCase) {
        List<Token> tokens = new ArrayList<>(text.length());
        for (int i = 0; i < text.length(); i++) {
            tokens.add(Token.literal(text.charAt(i)));
        }
        return new Glob(List.copyOf(tokens), ignoreCase);
    }

    /**
     * One element of a compiled pattern: {@code *}, or something that matches exactly one character. Sets keep their
     * single characters in {@code chars} and their ranges as pairs in {@code ranges}.
     */
    record Token(Kind kind, char literal, String chars, String ranges, boolean negated) {

        enum Kind { STAR, ANY, LITERAL, SET }

        static final Token STAR = new Token(Kind.STAR, '\0', "", "", false);
        static final Token ANY = new Token(Kind.ANY, '\0', "", "", false);

        static Token literal(char c) {
            return new Token(Kind.LITERAL, c, "", "", false);
        }

        /** The set of {@code [members]}, or {@code null} when it holds a reversed range. */
        static Token set(String members, boolean negated) {
            StringBuilder chars = new StringBuilder();
            StringBuilder ranges = new StringBuilder();
            int j = 0;
            while (j < members.length()) {
                char from = members.charAt(j);
                if (j + 2 < members.length() && members.charAt(j + 1) == '-') {
                    char to = members.charAt(j + 2);
                    if (to < from) {
                        return null;
                    }
                    ranges.append(from).append(to);
                    j += 3;
                } else {
                    chars.append(from);
                    j++;
                }
            }
            return new Token(Kind.SET, '\0', chars.toString(), ranges.toString(), negated);
        }

        boolean matches(char c, boolean ignoreCase) {
            return switch (kind) {
                case STAR, ANY -> true;
                case LITERAL -> c == literal || ignoreCase && sameIgnoringCase(c, literal);
                case SET -> inSet(c, ignoreCase) != negated;
            };
        }

        private boolean inSet(char c, boolean ignoreCase) {
            if (inSet(c)) {
                return true;
            }
            return ignoreCase && (inSet(Character.toLowerCase(c)) || inSet(Character.toUpperCase(c)));
        }

        private boolean inSet(char c) {
            if (chars.indexOf(c) >= 0) {
                return true;
            }
            for (int k = 0; k + 1 < ranges.length(); k += 2) {
                if (c >= ranges.charAt(k) && c <= ranges.charAt(k + 1)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean sameIgnoringCase(char a, char b) {
            return Character.toLowerCase(a) == Character.toLowerCase(b) || Character.toUpperCase(a) == Character.toUpperCase(b);
        }
    }

    /**
     * A compiled wildcard pattern. Matching is the classic two-pointer walk that only ever backtracks to the most
     * recent {@code *}, so it takes at most (text length × pattern length) steps whatever the pattern: a request
     * such as {@code q=*?*?*?*?*?*?*?*?*?X} cannot keep the server busy the way a backtracking regular expression would.
     */
    record Glob(List<Token> tokens, boolean ignoreCase) {

        boolean matches(String text) {
            int t = 0;
            int p = 0;
            int starP = -1;
            int starT = -1;
            while (t < text.length()) {
                if (p < tokens.size() && tokens.get(p) == Token.STAR) {
                    starP = p++;
                    starT = t;
                } else if (p < tokens.size() && tokens.get(p).matches(text.charAt(t), ignoreCase)) {
                    p++;
                    t++;
                } else if (starP >= 0) {
                    p = starP + 1;
                    t = ++starT;
                } else {
                    return false;
                }
            }
            while (p < tokens.size() && tokens.get(p) == Token.STAR) {
                p++;
            }
            return p == tokens.size();
        }
    }

    /** Artifactory spells an absent user and channel as {@code @_/_}; the same reference without it is the same recipe. */
    public static String canonical(String reference) {
        String ref = reference == null ? "" : reference.trim();
        if (ref.endsWith("@_/_")) {
            return ref.substring(0, ref.length() - 4);
        }
        if (ref.endsWith("@_")) {
            return ref.substring(0, ref.length() - 2);
        }
        return ref;
    }

    public static ObjectNode resultsDocument(Collection<String> references) {
        ObjectNode doc = NODES.objectNode();
        ArrayNode array = doc.putArray("results");
        references.forEach(array::add);
        return doc;
    }

    public static List<String> parseResults(JsonNode doc) {
        List<String> results = new ArrayList<>();
        JsonNode array = doc == null ? null : doc.path("results");
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                if (item.isString() && !item.stringValue().isBlank()) {
                    results.add(canonical(item.stringValue()));
                }
            }
        }
        return results;
    }

    /** The union of several result lists, de-duplicated and sorted. */
    public static List<String> merge(List<List<String>> sources) {
        TreeSet<String> union = new TreeSet<>();
        for (List<String> source : sources) {
            for (String reference : source) {
                union.add(canonical(reference));
            }
        }
        return new ArrayList<>(union);
    }
}
