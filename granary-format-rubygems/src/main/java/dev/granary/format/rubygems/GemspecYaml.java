package dev.granary.format.rubygems;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Reads the YAML gemspec of a {@code .gem} ({@code metadata.gz}) into a {@link GemSpec}.
 *
 * <p>Ruby's YAML carries Ruby class tags ({@code !ruby/object:Gem::Specification}, {@code Gem::Version},
 * {@code Gem::Requirement}, {@code Gem::Dependency}, {@code Gem::Platform}). They are read as plain maps and
 * nothing is ever instantiated from a tag. Scalars stay strings (so {@code version: 1.10} is not turned into the
 * float {@code 1.1}); only {@code null} is resolved implicitly.
 */
public final class GemspecYaml {

    private static final Pattern DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})");
    private static final int MAX_CODE_POINTS = 8 * 1024 * 1024;

    private GemspecYaml() {
    }

    /** Parses a gemspec; {@link InvalidGemException} for anything that is not one. */
    public static GemSpec parse(String yamlText) {
        Object root;
        try {
            root = yaml().load(yamlText);
        } catch (YAMLException | IllegalArgumentException | ClassCastException | StackOverflowError e) {
            throw new InvalidGemException("The gemspec (metadata.gz) is not valid YAML: " + firstLine(e.getMessage()), e);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new InvalidGemException("The gemspec (metadata.gz) is not a Gem::Specification");
        }
        return fromMap(map);
    }

    static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);
        options.setMaxAliasesForCollections(1000);
        options.setCodePointLimit(MAX_CODE_POINTS);
        options.setNestingDepthLimit(64);
        options.setAllowRecursiveKeys(false);
        options.setTagInspector(tag -> false);
        DumperOptions dumper = new DumperOptions();
        return new Yaml(new PlainConstructor(options), new Representer(dumper), dumper, options, new NullOnlyResolver());
    }

    static GemSpec fromMap(Map<?, ?> map) {
        String name = string(map.get("name"));
        if (name == null || name.isBlank()) {
            throw new InvalidGemException("The gemspec has no name");
        }
        name = name.strip();
        if (!GemNames.isValidName(name)) {
            throw new InvalidGemException("'" + name + "' is not a valid gem name (letters, digits, '.', '-' and '_' only, "
                    + "with at least one letter)");
        }
        if (!GemNames.isPublishableName(name)) {
            throw new InvalidGemException("'" + name + "' is not a valid gem name (it cannot begin with a period, dash, "
                    + "or underscore)");
        }
        String rawVersion = versionString(map.get("version"));
        if (rawVersion == null) {
            throw new InvalidGemException("The gemspec of " + name + " has no version");
        }
        String version = GemVersion.parse(rawVersion)
                .orElseThrow(() -> new InvalidGemException("'" + rawVersion + "' is not a valid gem version"))
                .toString();
        String platform = platformString(map.get("platform"));
        if (!GemPlatform.isRuby(platform)) {
            if (!GemPlatform.isValid(platform)) {
                throw new InvalidGemException("'" + platform + "' is not a valid platform");
            }
            platform = GemPlatform.normalise(platform);
            if (!GemPlatform.isValid(platform)) {
                throw new InvalidGemException("'" + platform + "' is not a valid platform");
            }
        }
        Object email = map.get("email");
        List<String> licenses = strings(map.get("licenses"));
        if (licenses.isEmpty() && string(map.get("license")) != null) {
            licenses = List.of(string(map.get("license")));
        }
        List<GemDependency> dependencies = new ArrayList<>();
        if (map.get("dependencies") instanceof List<?> deps) {
            for (Object dep : deps) {
                dependencies.add(dependency(name, dep));
            }
        }
        return new GemSpec(name, version, platform,
                string(map.get("summary")),
                string(map.get("description")),
                strings(map.get("authors")),
                strings(email),
                email instanceof List<?>,
                string(map.get("homepage")),
                licenses,
                metadata(map.get("metadata")),
                dependencies,
                requirement(map.get("required_ruby_version"), "required_ruby_version"),
                requirement(map.get("required_rubygems_version"), "required_rubygems_version"),
                string(map.get("rubygems_version")),
                integer(map.get("specification_version"), 4),
                date(map.get("date")));
    }

    private static GemDependency dependency(String gem, Object value) {
        if (!(value instanceof Map<?, ?> dep)) {
            throw new InvalidGemException("A dependency of " + gem + " is not a Gem::Dependency");
        }
        String name = string(dep.get("name"));
        if (name == null || !GemNames.isValidName(name.strip())) {
            throw new InvalidGemException("A dependency of " + gem + " has an invalid name '" + name + "'");
        }
        Object requirement = dep.get("requirement") != null ? dep.get("requirement") : dep.get("version_requirements");
        String type = string(dep.get("type"));
        type = type == null ? GemDependency.RUNTIME : type.strip().replaceFirst("^:", "");
        return new GemDependency(name.strip(), type, requirement(requirement, "dependency " + name));
    }

    /** {@code {requirements: [[op, {version: v}], ...]}}, a {@code "op v, op v"} string, or nothing (any version). */
    static GemRequirement requirement(Object value, String what) {
        try {
            if (value == null) {
                return GemRequirement.any();
            }
            if (value instanceof String s) {
                return GemRequirement.parse(s);
            }
            if (value instanceof Map<?, ?> map) {
                Object requirements = map.get("requirements");
                if (!(requirements instanceof List<?> list) || list.isEmpty()) {
                    return GemRequirement.any();
                }
                List<GemRequirement.Constraint> constraints = new ArrayList<>();
                for (Object pair : list) {
                    if (pair instanceof List<?> p && p.size() == 2) {
                        String op = string(p.get(0));
                        String version = versionString(p.get(1));
                        if (op == null || version == null) {
                            throw new IllegalArgumentException("incomplete constraint " + p);
                        }
                        constraints.add(GemRequirement.constraint(op.strip(), version));
                    } else if (pair instanceof String s) {
                        constraints.add(GemRequirement.constraint(s));
                    } else {
                        throw new IllegalArgumentException("unexpected constraint " + pair);
                    }
                }
                return new GemRequirement(constraints);
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidGemException("The " + what + " requirement is invalid: " + e.getMessage(), e);
        }
        throw new InvalidGemException("The " + what + " requirement is not a Gem::Requirement");
    }

    /** {@code {version: "1.0"}} (a tagged {@code Gem::Version}) or a plain scalar. */
    private static String versionString(Object value) {
        if (value instanceof Map<?, ?> map) {
            return string(map.get("version"));
        }
        return string(value);
    }

    /** A platform string, or a {@code Gem::Platform} map ({@code cpu}, {@code os}, {@code version}). */
    private static String platformString(Object value) {
        if (value instanceof Map<?, ?> map) {
            String platform = new GemPlatform(string(map.get("cpu")), string(map.get("os")), string(map.get("version"))).toString();
            return platform.isEmpty() ? GemPlatform.RUBY : platform;
        }
        String s = string(value);
        return s == null || s.isBlank() ? GemPlatform.RUBY : s.strip();
    }

    private static Map<String, String> metadata(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = string(e.getKey());
                String v = string(e.getValue());
                if (key != null && v != null) {
                    result.put(key, v);
                }
            }
        }
        return result;
    }

    private static LocalDate date(Object value) {
        if (value instanceof Date d) {
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        String s = string(value);
        if (s != null) {
            Matcher m = DATE.matcher(s.strip());
            if (m.find()) {
                try {
                    return LocalDate.parse(m.group(1) + "-" + m.group(2) + "-" + m.group(3));
                } catch (DateTimeParseException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static int integer(Object value, int defaultValue) {
        String s = string(value);
        if (s == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return null;
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                String s = string(o);
                if (s != null) {
                    result.add(s);
                }
            }
        } else {
            String s = string(value);
            if (s != null && !s.isEmpty()) {
                result.add(s);
            }
        }
        return result;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unreadable";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }

    /**
     * Builds plain maps, lists and strings whatever the tag. {@code !binary} scalars (Base64, as old Ruby YAML
     * wrote non-ASCII strings) are decoded as UTF-8.
     */
    private static final class PlainConstructor extends SafeConstructor {

        PlainConstructor(LoaderOptions options) {
            super(options);
            AbstractConstruct plain = new AbstractConstruct() {
                @Override
                public Object construct(Node node) {
                    return switch (node) {
                        case ScalarNode scalar -> scalar(scalar);
                        case SequenceNode sequence -> constructSequence(sequence);
                        case MappingNode mapping -> constructMapping(mapping);
                        default -> null;
                    };
                }
            };
            this.yamlConstructors.put(null, plain);
            this.yamlConstructors.put(Tag.BINARY, new AbstractConstruct() {
                @Override
                public Object construct(Node node) {
                    return binary((ScalarNode) node);
                }
            });
        }

        private Object scalar(ScalarNode node) {
            if (node.getTag().getValue().endsWith("binary")) {
                return binary(node);
            }
            return constructScalar(node);
        }

        private String binary(ScalarNode node) {
            String encoded = constructScalar(node).replaceAll("\\s", "");
            try {
                return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return encoded;
            }
        }
    }

    /** Resolves only {@code null} implicitly; every other plain scalar stays a string. */
    private static final class NullOnlyResolver extends Resolver {
        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.NULL, NULL, "~nN\0");
            addImplicitResolver(Tag.NULL, EMPTY, null);
        }
    }
}
