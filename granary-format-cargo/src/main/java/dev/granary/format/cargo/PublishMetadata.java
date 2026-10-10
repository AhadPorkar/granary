package dev.granary.format.cargo;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON metadata cargo sends with a publish, validated, and its translation into an index line the way
 * crates.io does it: {@code version_req} becomes {@code req}, a renamed dependency
 * ({@code explicit_name_in_toml}) is listed under its alias with the real crate in {@code package}, features
 * that use {@code dep:} or {@code ?/} syntax move to {@code features2} with {@code "v":2}.
 */
final class PublishMetadata {

    static final Set<String> DEPENDENCY_KINDS = Set.of("normal", "dev", "build");
    private static final DateTimeFormatter PUBTIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final ObjectNode json;
    private final String name;
    private final String vers;
    private final List<ObjectNode> deps;

    private PublishMetadata(ObjectNode json, String name, String vers, List<ObjectNode> deps) {
        this.json = json;
        this.name = name;
        this.vers = vers;
        this.deps = deps;
    }

    /** Validates what cargo sent; every problem is a 400 with a message cargo shows to the user. */
    static PublishMetadata of(ObjectNode json) {
        String name = requiredString(json, "name", "crate name");
        String problem = CrateNames.publishProblem(name);
        if (problem != null) {
            throw CargoError.badRequest(problem);
        }
        String vers = requiredString(json, "vers", "version");
        if (!CargoVersion.isValid(vers)) {
            throw CargoError.badRequest("version `" + vers + "` of crate `" + name + "` is not a valid semver version");
        }
        List<ObjectNode> deps = new ArrayList<>();
        JsonNode rawDeps = json.get("deps");
        if (rawDeps != null && !rawDeps.isNull()) {
            if (!rawDeps.isArray()) {
                throw CargoError.badRequest("`deps` must be an array");
            }
            for (JsonNode dep : rawDeps) {
                deps.add(dependency(dep));
            }
        }
        features(json.get("features"));
        optionalString(json, "links");
        optionalString(json, "rust_version");
        optionalString(json, "description");
        return new PublishMetadata(json, name, vers, deps);
    }

    String name() {
        return name;
    }

    String vers() {
        return vers;
    }

    String description() {
        return CargoJson.string(json, "description");
    }

    ObjectNode json() {
        return json;
    }

    /** The index line of this version, field for field in the order crates.io writes them. */
    ObjectNode toIndexLine(String cksum, Instant published) {
        ObjectNode line = CargoJson.object();
        line.put("name", name);
        line.put("vers", vers);
        ArrayNode depsOut = line.putArray("deps");
        List<ObjectNode> converted = new ArrayList<>();
        for (ObjectNode dep : deps) {
            converted.add(indexDependency(dep));
        }
        converted.sort(Comparator.comparing((ObjectNode d) -> CargoJson.string(d, "name"))
                .thenComparing(d -> CargoJson.string(d, "kind"), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(d -> CargoJson.string(d, "req")));
        converted.forEach(depsOut::add);
        line.put("cksum", cksum);
        Map<String, List<String>> features = features(json.get("features"));
        Map<String, List<String>> plain = new TreeMap<>();
        Map<String, List<String>> extended = new TreeMap<>();
        features.forEach((feature, values) -> {
            boolean newSyntax = values.stream().anyMatch(v -> v.startsWith("dep:") || v.contains("?/"));
            (newSyntax ? extended : plain).put(feature, values);
        });
        putFeatures(line.putObject("features"), plain);
        if (!extended.isEmpty()) {
            putFeatures(line.putObject("features2"), extended);
        }
        line.put("yanked", false);
        String links = CargoJson.string(json, "links");
        if (links != null) {
            line.put("links", links);
        }
        String rustVersion = CargoJson.string(json, "rust_version");
        if (rustVersion != null) {
            line.put("rust_version", rustVersion);
        }
        if (!extended.isEmpty()) {
            line.put("v", 2);
        }
        line.put("pubtime", PUBTIME.format(published.truncatedTo(ChronoUnit.SECONDS)));
        return line;
    }

    private static ObjectNode indexDependency(ObjectNode dep) {
        ObjectNode out = CargoJson.object();
        String name = CargoJson.string(dep, "name");
        String alias = CargoJson.string(dep, "explicit_name_in_toml");
        out.put("name", alias != null ? alias : name);
        out.put("req", CargoJson.string(dep, "version_req"));
        ArrayNode features = out.putArray("features");
        JsonNode rawFeatures = dep.get("features");
        if (rawFeatures != null && rawFeatures.isArray()) {
            rawFeatures.forEach(f -> features.add(f.stringValue()));
        }
        out.put("optional", bool(dep, "optional", false));
        out.put("default_features", bool(dep, "default_features", true));
        String target = CargoJson.string(dep, "target");
        if (target != null) {
            out.put("target", target);
        } else {
            out.putNull("target");
        }
        String kind = CargoJson.string(dep, "kind");
        out.put("kind", kind == null ? "normal" : kind);
        String registry = CargoJson.string(dep, "registry");
        if (registry != null) {
            out.put("registry", registry);
        }
        if (alias != null) {
            out.put("package", name);
        }
        // artifact dependencies (cargo -Z bindeps) travel through unchanged
        for (String field : List.of("artifact", "bindep_target", "lib")) {
            JsonNode value = dep.get(field);
            if (value != null && !value.isNull() && !(value.isBoolean() && !value.booleanValue())) {
                out.set(field, value.deepCopy());
            }
        }
        return out;
    }

    private static ObjectNode dependency(JsonNode dep) {
        if (!dep.isObject()) {
            throw CargoError.badRequest("every entry of `deps` must be an object");
        }
        ObjectNode node = (ObjectNode) dep;
        String name = requiredString(node, "name", "dependency name");
        if (!CrateNames.isValidLookup(name)) {
            throw CargoError.badRequest("dependency name `" + name + "` is not a valid crate name");
        }
        String req = requiredString(node, "version_req", "version requirement of dependency `" + name + "`");
        if (req.isBlank()) {
            throw CargoError.badRequest("dependency `" + name + "` has an empty version requirement");
        }
        String alias = optionalString(node, "explicit_name_in_toml");
        if (alias != null && !CrateNames.isValidLookup(alias)) {
            throw CargoError.badRequest("dependency rename `" + alias + "` is not a valid crate name");
        }
        String kind = optionalString(node, "kind");
        if (kind != null && !DEPENDENCY_KINDS.contains(kind)) {
            throw CargoError.badRequest("dependency `" + name + "` has an unknown kind `" + kind + "`");
        }
        optionalString(node, "target");
        optionalString(node, "registry");
        JsonNode features = node.get("features");
        if (features != null && !features.isNull()) {
            if (!features.isArray()) {
                throw CargoError.badRequest("features of dependency `" + name + "` must be an array of strings");
            }
            for (JsonNode f : features) {
                if (!f.isString()) {
                    throw CargoError.badRequest("features of dependency `" + name + "` must be an array of strings");
                }
            }
        }
        for (String flag : List.of("optional", "default_features")) {
            JsonNode value = node.get(flag);
            if (value != null && !value.isNull() && !value.isBoolean()) {
                throw CargoError.badRequest("`" + flag + "` of dependency `" + name + "` must be a boolean");
            }
        }
        return node;
    }

    /** The feature table, validated: an object whose values are arrays of strings. */
    private static Map<String, List<String>> features(JsonNode node) {
        Map<String, List<String>> out = new TreeMap<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (!node.isObject()) {
            throw CargoError.badRequest("`features` must be an object");
        }
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (!e.getValue().isArray()) {
                throw CargoError.badRequest("feature `" + e.getKey() + "` must be an array of strings");
            }
            List<String> values = new ArrayList<>();
            for (JsonNode v : e.getValue()) {
                if (!v.isString()) {
                    throw CargoError.badRequest("feature `" + e.getKey() + "` must be an array of strings");
                }
                values.add(v.stringValue());
            }
            out.put(e.getKey(), values);
        }
        return out;
    }

    private static void putFeatures(ObjectNode target, Map<String, List<String>> features) {
        features.forEach((feature, values) -> {
            ArrayNode array = target.putArray(feature);
            values.forEach(array::add);
        });
    }

    private static String requiredString(JsonNode node, String field, String what) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw CargoError.badRequest("missing " + what + " (`" + field + "`) in the publish metadata");
        }
        if (!value.isString()) {
            throw CargoError.badRequest("`" + field + "` must be a string");
        }
        return value.stringValue();
    }

    private static String optionalString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw CargoError.badRequest("`" + field + "` must be a string or null");
        }
        return value.stringValue();
    }

    private static boolean bool(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.booleanValue() : defaultValue;
    }
}
