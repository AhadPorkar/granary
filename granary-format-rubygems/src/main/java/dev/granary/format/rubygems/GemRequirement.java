package dev.granary.format.rubygems;

import dev.granary.format.rubygems.marshal.Ruby;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A {@code Gem::Requirement}: constraints such as {@code >= 1.0} and {@code < 3} that must all hold.
 *
 * @param constraints the constraints in declaration order; never empty ({@code >= 0} stands for "any version")
 */
public record GemRequirement(List<Constraint> constraints) {

    public static final Set<String> OPERATORS = Set.of("=", "!=", ">", "<", ">=", "<=", "~>");
    private static final List<Constraint> DEFAULT_CONSTRAINTS = List.of(new Constraint(">=", "0"));
    private static final GemRequirement DEFAULT = new GemRequirement(DEFAULT_CONSTRAINTS);

    /** One constraint: an operator and a version string. */
    public record Constraint(String operator, String version) {
        @Override
        public String toString() {
            return operator + " " + version;
        }
    }

    public GemRequirement {
        constraints = constraints == null || constraints.isEmpty() ? DEFAULT_CONSTRAINTS : List.copyOf(constraints);
    }

    /** {@code Gem::Requirement.default}: any version. */
    public static GemRequirement any() {
        return DEFAULT;
    }

    /**
     * Parses {@code ">= 1.0, < 3"} (a bare version means {@code =}). Empty means any version.
     *
     * @throws IllegalArgumentException for an unknown operator or an invalid version
     */
    public static GemRequirement parse(String text) {
        if (text == null || text.isBlank()) {
            return DEFAULT;
        }
        List<Constraint> constraints = new ArrayList<>();
        for (String part : text.split("[,&]")) {
            constraints.add(constraint(part.strip()));
        }
        return new GemRequirement(constraints);
    }

    /** One {@code "op version"} or bare version, as {@code Gem::Requirement.parse} accepts it. */
    public static Constraint constraint(String text) {
        String s = text.strip();
        String op = "=";
        for (String candidate : List.of(">=", "<=", "!=", "~>", "=", ">", "<")) {
            if (s.startsWith(candidate)) {
                op = candidate;
                s = s.substring(candidate.length()).strip();
                break;
            }
        }
        Optional<GemVersion> version = GemVersion.parse(s);
        if (version.isEmpty() || s.isEmpty()) {
            throw new IllegalArgumentException("Illformed requirement \"" + text + "\"");
        }
        return new Constraint(op, version.get().toString());
    }

    public static Constraint constraint(String operator, String version) {
        if (!OPERATORS.contains(operator)) {
            throw new IllegalArgumentException("Illformed requirement operator \"" + operator + "\"");
        }
        Optional<GemVersion> v = GemVersion.parse(version);
        if (v.isEmpty()) {
            throw new IllegalArgumentException("Illformed requirement version \"" + version + "\"");
        }
        return new Constraint(operator, v.get().toString());
    }

    /** Whether this is the default requirement, which matches every version. */
    public boolean isAny() {
        return constraints.size() == 1 && constraints.getFirst().operator().equals(">=")
                && GemVersion.of(constraints.getFirst().version()).compareTo(GemVersion.of("0")) == 0;
    }

    /** {@code Gem::Requirement#to_s}: the constraints joined with {@code ", "}. */
    @Override
    public String toString() {
        return String.join(", ", constraints.stream().map(Constraint::toString).toList());
    }

    /** The compact index form: constraints sorted and joined with {@code &}, as rubygems.org writes them. */
    public String toCompactIndex() {
        return String.join("&", constraints.stream().map(Constraint::toString).sorted().toList());
    }

    /** {@code Gem::Requirement} marshalled: {@code U:Gem::Requirement [[[op, Gem::Version], ...]]}. */
    public Ruby.UserMarshal toMarshal() {
        List<Object> pairs = new ArrayList<>();
        for (Constraint c : constraints) {
            pairs.add(List.of(c.operator(), Ruby.gemVersion(c.version())));
        }
        return new Ruby.UserMarshal("Gem::Requirement", List.of(pairs));
    }

    /** Reads a marshalled {@code Gem::Requirement}; anything unexpected yields "any version". */
    public static GemRequirement fromMarshal(Object value) {
        if (!(value instanceof Ruby.UserMarshal u) || !(u.data() instanceof List<?> data) || data.isEmpty()
                || !(data.getFirst() instanceof List<?> pairs)) {
            return DEFAULT;
        }
        List<Constraint> constraints = new ArrayList<>();
        for (Object pair : pairs) {
            if (pair instanceof List<?> p && p.size() == 2 && p.get(0) instanceof String op) {
                String version = Ruby.gemVersionString(p.get(1));
                if (version != null && OPERATORS.contains(op)) {
                    constraints.add(new Constraint(op, version));
                }
            }
        }
        return new GemRequirement(constraints);
    }
}
