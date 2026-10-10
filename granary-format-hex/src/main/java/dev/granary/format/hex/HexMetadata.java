package dev.granary.format.hex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code metadata.config} of a release tarball: what {@code mix hex.build} or {@code rebar3 hex}
 * wrote about the package. Only the fields the registry and the API need are typed; {@link #all()}
 * keeps everything else as parsed.
 *
 * @param requirements dependencies in every shape clients have used (tuples, maps, legacy property lists)
 */
public record HexMetadata(String name, String version, String app, String description, List<String> licenses,
                          Map<String, String> links, List<Requirement> requirements, List<String> buildTools,
                          String elixir, List<String> files, Map<String, Object> all) {

    /**
     * @param repository the repository the dependency comes from, or {@code null} when the metadata names none
     */
    public record Requirement(String name, String app, String requirement, boolean optional, String repository) {
    }

    public static final class MetadataException extends IllegalArgumentException {
        MetadataException(String message) {
            super(message);
        }
    }

    public HexMetadata {
        licenses = List.copyOf(licenses);
        links = Map.copyOf(links);
        requirements = List.copyOf(requirements);
        buildTools = List.copyOf(buildTools);
        files = List.copyOf(files);
    }

    public static HexMetadata parse(String text) {
        Map<String, Object> all;
        try {
            all = ErlangTerms.consult(text);
        } catch (ErlangTerms.ParseException e) {
            throw new MetadataException("metadata.config is not valid Erlang term syntax: " + e.getMessage());
        }
        String name = requireText(all, "name");
        if (!HexPath.isValidName(name)) {
            throw new MetadataException("invalid package name '" + name + "'");
        }
        String version = requireText(all, "version");
        if (!HexVersion.isValid(version)) {
            throw new MetadataException("invalid version '" + version + "': expected a semantic version");
        }
        String app = text(all.get("app"));
        return new HexMetadata(name, version, app == null || app.isBlank() ? name : app, text(all.get("description")),
                strings(all.get("licenses"), "licenses"), stringMap(all.get("links"), "links"),
                requirements(all.get("requirements")), strings(all.get("build_tools"), "build_tools"),
                text(all.get("elixir")), strings(all.get("files"), "files"), all);
    }

    private static String requireText(Map<String, Object> all, String key) {
        String value = text(all.get(key));
        if (value == null || value.isBlank()) {
            throw new MetadataException("metadata.config has no " + key);
        }
        return value;
    }

    /** Text values; atoms and numbers are accepted as text too since clients have been loose about them. */
    static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = ErlangTerms.asString(value);
        if (s != null) {
            return s;
        }
        if (value instanceof Number n) {
            return n.toString();
        }
        return null;
    }

    private static List<String> strings(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String s) {
            return List.of(s);
        }
        if (!(value instanceof List<?> list)) {
            throw new MetadataException(field + " must be a list");
        }
        List<String> result = new ArrayList<>();
        for (Object element : list) {
            String s = text(element);
            if (s == null) {
                throw new MetadataException(field + " must be a list of binaries");
            }
            result.add(s);
        }
        return result;
    }

    private static Map<String, String> stringMap(Object value, String field) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> props = ErlangTerms.asProperties(value);
        if (props == null) {
            if (value instanceof List<?> list && list.isEmpty()) {
                return Map.of();
            }
            throw new MetadataException(field + " must be a map or a property list");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            String v = text(e.getValue());
            if (v != null) {
                result.put(e.getKey(), v);
            }
        }
        return result;
    }

    /**
     * Requirements come as {@code [{<<"jason">>, [{<<"app">>,…}, …]}]} (current clients),
     * {@code #{<<"jason">> => #{…}}} (maps) or {@code [[{<<"name">>,<<"jason">>}, …]]} (legacy).
     */
    static List<Requirement> requirements(Object value) {
        if (value == null) {
            return List.of();
        }
        List<Requirement> result = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String name = text(e.getKey());
                if (name == null) {
                    throw new MetadataException("requirement names must be binaries");
                }
                result.add(requirement(name, e.getValue()));
            }
            return result;
        }
        if (!(value instanceof List<?> list)) {
            throw new MetadataException("requirements must be a list or a map");
        }
        for (Object element : list) {
            if (element instanceof ErlangTerms.Tuple tuple && tuple.size() == 2 && text(tuple.get(0)) != null) {
                result.add(requirement(text(tuple.get(0)), tuple.get(1)));
                continue;
            }
            Map<String, Object> legacy = ErlangTerms.asProperties(element);
            if (legacy != null && legacy.containsKey("name")) {
                String name = text(legacy.get("name"));
                Map<String, Object> rest = new LinkedHashMap<>(legacy);
                rest.remove("name");
                result.add(requirement(name, rest));
                continue;
            }
            throw new MetadataException("unsupported requirement entry: " + ErlangTerms.describe(element));
        }
        return result;
    }

    private static Requirement requirement(String name, Object spec) {
        if (name == null || !HexPath.isValidName(name)) {
            throw new MetadataException("invalid dependency name '" + name + "'");
        }
        Map<String, Object> props = ErlangTerms.asProperties(spec);
        if (props == null) {
            throw new MetadataException("requirement " + name + " must be a map or a property list");
        }
        String requirement = text(props.get("requirement"));
        if (requirement == null || requirement.isBlank()) {
            throw new MetadataException("requirement " + name + " has no version requirement");
        }
        String app = text(props.get("app"));
        Object optional = props.get("optional");
        boolean isOptional = optional instanceof Boolean b ? b : "true".equals(text(optional));
        String repository = text(props.get("repository"));
        return new Requirement(name, app == null || app.isBlank() ? name : app, requirement, isOptional,
                repository == null || repository.isBlank() ? null : repository);
    }
}
