package dev.granary.format.pub;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A package's {@code pubspec.yaml}, converted to the JSON object pub repositories serve as {@code pubspec} in
 * every version entry. Everything in the file is kept (dependencies, environment, homepage, repository,
 * topics, screenshots, platforms, executables, …); only {@code name} and {@code version} are interpreted.
 *
 * @param name    the package name
 * @param version the version, exactly as written in the file
 * @param json    the whole pubspec as JSON
 */
public record Pubspec(String name, PubVersion version, ObjectNode json) {

    /** Thrown when the pubspec cannot be published; the message is shown to the publisher. */
    public static final class InvalidPubspecException extends Exception {
        public InvalidPubspecException(String message) {
            super(message);
        }
    }

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);
    private static final int MAX_DEPTH = 64;

    public static Pubspec parse(byte[] yaml) throws InvalidPubspecException {
        Object document = load(yaml);
        if (!(document instanceof Map<?, ?> map)) {
            throw new InvalidPubspecException("pubspec.yaml must be a YAML mapping");
        }
        ObjectNode json = (ObjectNode) toJson(map, 0);
        String name = string(json, "name")
                .orElseThrow(() -> new InvalidPubspecException("pubspec.yaml has no \"name\" field"));
        Optional<String> nameProblem = PubNames.publishProblem(name);
        if (nameProblem.isPresent()) {
            throw new InvalidPubspecException(nameProblem.get());
        }
        String versionText = string(json, "version")
                .orElseThrow(() -> new InvalidPubspecException("pubspec.yaml has no \"version\" field"));
        PubVersion version = PubVersion.parse(versionText).orElseThrow(() -> new InvalidPubspecException(
                "\"" + versionText + "\" in pubspec.yaml is not a valid version (expected major.minor.patch, "
                        + "e.g. 1.0.0 or 2.1.0-dev.1)"));
        // keep the canonical spelling the version was validated with
        json.put("version", version.text());
        return new Pubspec(name, version, json);
    }

    private static Object load(byte[] yaml) throws InvalidPubspecException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(MAX_DEPTH);
        options.setCodePointLimit(Math.max(1024, yaml.length * 2));
        options.setAllowRecursiveKeys(false);
        try {
            return new Yaml(new SafeConstructor(options)).load(new String(yaml, StandardCharsets.UTF_8));
        } catch (YAMLException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new InvalidPubspecException("pubspec.yaml is not valid YAML: " + firstLines(message));
        } catch (RuntimeException e) {
            // SnakeYAML reports some malformed input with plain runtime exceptions
            throw new InvalidPubspecException("pubspec.yaml is not valid YAML");
        }
    }

    /** {@code name} and {@code version} as strings; YAML may have typed them as numbers. */
    private static Optional<String> string(ObjectNode json, String field) {
        JsonNode node = json.get(field);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (node.isString()) {
            String s = node.stringValue().trim();
            return s.isEmpty() ? Optional.empty() : Optional.of(s);
        }
        if (node.isNumber() || node.isBoolean()) {
            return Optional.of(node.asString());
        }
        return Optional.empty();
    }

    /** YAML values as JSON: mappings with string keys, sequences, scalars; timestamps and binaries as strings. */
    static JsonNode toJson(Object value, int depth) throws InvalidPubspecException {
        if (depth > MAX_DEPTH) {
            throw new InvalidPubspecException("pubspec.yaml is nested too deeply");
        }
        return switch (value) {
            case null -> NODES.nullNode();
            case Map<?, ?> map -> {
                ObjectNode object = NODES.objectNode();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    object.set(String.valueOf(e.getKey()), toJson(e.getValue(), depth + 1));
                }
                yield object;
            }
            case Collection<?> list -> {
                ArrayNode array = NODES.arrayNode();
                for (Object item : list) {
                    array.add(toJson(item, depth + 1));
                }
                yield array;
            }
            case String s -> NODES.stringNode(s);
            case Boolean b -> NODES.booleanNode(b);
            case Integer i -> NODES.numberNode(i);
            case Long l -> NODES.numberNode(l);
            case BigInteger big -> NODES.numberNode(big);
            case Double d -> d.isNaN() || d.isInfinite() ? NODES.stringNode(d.toString()) : NODES.numberNode(d);
            case Float f -> NODES.numberNode(f.doubleValue());
            case BigDecimal decimal -> NODES.numberNode(decimal);
            case Date date -> NODES.stringNode(ISO.format(date.toInstant()));
            case byte[] bytes -> NODES.stringNode(Base64.getEncoder().encodeToString(bytes));
            default -> NODES.stringNode(String.valueOf(value));
        };
    }

    private static String firstLines(String message) {
        String[] lines = message.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, lines.length); i++) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(lines[i].trim());
        }
        return sb.toString();
    }
}
