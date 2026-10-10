package dev.granary.format.helm;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.reader.UnicodeReader;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * YAML for charts and indexes. Loading is safe (no custom tags, alias expansion limited) and keeps scalars as
 * the text that was written: {@code version: 1.10} stays {@code "1.10"} instead of becoming the number 1.1, and
 * {@code created: 2021-03-02T18:36:45Z} stays a string instead of a {@link Date}. Only {@code true}/{@code false}
 * and null are interpreted, which is all a chart's metadata ever needs. Dumping produces block-style YAML the way
 * {@code helm repo index} writes it, quoting any string that would otherwise read back as a number or date.
 */
public final class HelmYaml {

    /** Chart.yaml and provenance documents are small; Helm itself refuses files above 5 MiB inside a chart. */
    public static final int SMALL_DOCUMENT_LIMIT = 5 * 1024 * 1024;
    /** Upstream indexes of large public repositories run to tens of megabytes. */
    public static final int INDEX_LIMIT = 256 * 1024 * 1024;

    private HelmYaml() {
    }

    /** Loads one YAML document; the result consists of maps with string keys, lists, strings, booleans and nulls. */
    public static Object load(byte[] bytes, int codePointLimit) {
        return load(new ByteArrayInputStream(bytes), codePointLimit);
    }

    public static Object load(InputStream in, int codePointLimit) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(codePointLimit);
        options.setMaxAliasesForCollections(50);
        options.setAllowRecursiveKeys(false);
        options.setNestingDepthLimit(64);
        Yaml yaml = new Yaml(new SafeConstructor(options), new Representer(dumperOptions()), dumperOptions(), options,
                new LiteralResolver());
        try {
            return normalise(yaml.load(new UnicodeReader(in)));
        } catch (YAMLException | ClassCastException | IllegalArgumentException e) {
            throw new InvalidYamlException(firstLine(e.getMessage()), e);
        } catch (StackOverflowError e) {
            throw new InvalidYamlException("document is nested too deeply", e);
        }
    }

    /** Loads a document that must be a mapping (an index, a Chart.yaml); an empty document is an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadMap(byte[] bytes, int codePointLimit) {
        Object root = load(bytes, codePointLimit);
        if (root == null) {
            return new LinkedHashMap<>();
        }
        if (!(root instanceof Map<?, ?>)) {
            throw new InvalidYamlException("expected a mapping at the top level", null);
        }
        return (Map<String, Object>) root;
    }

    public static byte[] dump(Map<String, Object> document) {
        DumperOptions options = dumperOptions();
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()), new Representer(options), options);
        return yaml.dump(document).getBytes(StandardCharsets.UTF_8);
    }

    private static DumperOptions dumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setWidth(Integer.MAX_VALUE);
        options.setSplitLines(false);
        options.setAllowUnicode(true);
        options.setNonPrintableStyle(DumperOptions.NonPrintableStyle.ESCAPE);
        options.setDereferenceAliases(true);
        options.setLineBreak(DumperOptions.LineBreak.UNIX);
        return options;
    }

    /** Turns whatever SnakeYAML built into plain maps (string keys), lists, strings, booleans and nulls. */
    static Object normalise(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(scalar(e.getKey()), normalise(e.getValue()));
            }
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>(collection.size());
            for (Object item : collection) {
                result.add(normalise(item));
            }
            return result;
        }
        if (value instanceof Object[] array) {
            return normalise(List.of(array));
        }
        return scalar(value);
    }

    private static String scalar(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Date date) {
            return DateTimeFormatter.ISO_INSTANT.format(date.toInstant());
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return value.toString();
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "malformed YAML";
        }
        String line = message.strip();
        int nl = line.indexOf('\n');
        line = nl > 0 ? line.substring(0, nl) : line;
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    /** Thrown for anything SnakeYAML cannot read or that has the wrong shape. */
    public static final class InvalidYamlException extends RuntimeException {
        public InvalidYamlException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Resolves only booleans, nulls and merge keys; every other plain scalar stays a string. */
    static final class LiteralResolver extends Resolver {
        private static final Pattern BOOLEAN = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");

        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, BOOLEAN, "tTfF");
            addImplicitResolver(Tag.NULL, NULL, "~nN\0");
            addImplicitResolver(Tag.NULL, EMPTY, null);
            addImplicitResolver(Tag.MERGE, MERGE, "<");
        }
    }
}
