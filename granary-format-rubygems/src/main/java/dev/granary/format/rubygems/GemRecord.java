package dev.granary.format.rubygems;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a hosted repository remembers about one pushed gem version, stored as a small JSON document next to the
 * {@code .gem} (see {@link RubygemsHosted#recordPath}). Every index document of a hosted repository is built from
 * these records, so they are the single source of truth: deleting a component in the UI removes its record with
 * the files, and the indexes follow on the next read.
 *
 * @param name                    gem name
 * @param version                 version string
 * @param platform                {@code ruby} or a platform
 * @param sha256                  SHA-256 of the {@code .gem} as stored (the compact index {@code checksum})
 * @param size                    size of the {@code .gem} in bytes
 * @param dependencies            runtime and development dependencies
 * @param requiredRubyVersion     {@code required_ruby_version} as {@code Gem::Requirement#to_s} prints it
 * @param requiredRubygemsVersion {@code required_rubygems_version}
 * @param summary                 summary
 * @param description             description, may be {@code null}
 * @param authors                 authors
 * @param licenses                licenses
 * @param homepage                homepage, may be {@code null}
 * @param metadata                the gemspec metadata hash
 * @param rubygemsVersion         RubyGems version that built the gem
 * @param createdAt               when the version was pushed
 * @param yanked                  whether the version is yanked (hidden from every index, file kept)
 * @param yankedAt                when it was yanked, or {@code null}
 * @param pushedBy                user that pushed it
 */
public record GemRecord(String name, String version, String platform, String sha256, long size,
                        List<Dependency> dependencies, String requiredRubyVersion, String requiredRubygemsVersion,
                        String summary, String description, List<String> authors, List<String> licenses,
                        String homepage, Map<String, String> metadata, String rubygemsVersion, Instant createdAt,
                        boolean yanked, Instant yankedAt, String pushedBy) {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** A dependency as the indexes print it. */
    public record Dependency(String name, String requirement, String type) {
        public Dependency {
            Objects.requireNonNull(name, "name");
            requirement = requirement == null || requirement.isBlank() ? ">= 0" : requirement;
            type = type == null ? GemDependency.RUNTIME : type;
        }

        public boolean runtime() {
            return !GemDependency.DEVELOPMENT.equals(type);
        }
    }

    public GemRecord {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        platform = GemPlatform.isRuby(platform) ? GemPlatform.RUBY : platform;
        sha256 = sha256 == null ? "" : sha256;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        requiredRubyVersion = requiredRubyVersion == null || requiredRubyVersion.isBlank() ? ">= 0" : requiredRubyVersion;
        requiredRubygemsVersion = requiredRubygemsVersion == null || requiredRubygemsVersion.isBlank()
                ? ">= 0" : requiredRubygemsVersion;
        summary = summary == null ? "" : summary;
        authors = authors == null ? List.of() : List.copyOf(authors);
        licenses = licenses == null ? List.of() : List.copyOf(licenses);
        metadata = metadata == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** The record of a freshly pushed gem. */
    public static GemRecord of(GemSpec spec, String sha256, long size, Instant createdAt, String pushedBy) {
        List<Dependency> deps = new ArrayList<>();
        for (GemDependency d : spec.dependencies()) {
            deps.add(new Dependency(d.name(), d.requirement().toString(), d.type()));
        }
        return new GemRecord(spec.name(), spec.version(), spec.platform(), sha256, size, deps,
                spec.requiredRubyVersion().toString(), spec.requiredRubygemsVersion().toString(), spec.summary(),
                spec.description(), spec.authors(), spec.licenses(), spec.homepage(), spec.metadata(),
                spec.rubygemsVersion(), createdAt, false, null, pushedBy);
    }

    public String fullName() {
        return GemNames.fullName(name, version, platform);
    }

    public String versionKey() {
        return GemNames.versionKey(version, platform);
    }

    public boolean prerelease() {
        return GemVersion.isPrerelease(version);
    }

    public SpecTuple tuple() {
        return new SpecTuple(name, version, platform);
    }

    public GemRecord withYanked(boolean yanked, Instant at) {
        return new GemRecord(name, version, platform, sha256, size, dependencies, requiredRubyVersion,
                requiredRubygemsVersion, summary, description, authors, licenses, homepage, metadata, rubygemsVersion,
                createdAt, yanked, yanked ? at : null, pushedBy);
    }

    // --- JSON ---------------------------------------------------------------------------------------

    public byte[] toJson() {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", name);
        node.put("version", version);
        node.put("platform", platform);
        node.put("sha256", sha256);
        node.put("size", size);
        ArrayNode deps = node.putArray("dependencies");
        for (Dependency d : dependencies) {
            deps.addObject().put("name", d.name()).put("requirement", d.requirement()).put("type", d.type());
        }
        node.put("requiredRubyVersion", requiredRubyVersion);
        node.put("requiredRubygemsVersion", requiredRubygemsVersion);
        node.put("summary", summary);
        node.put("description", description);
        ArrayNode a = node.putArray("authors");
        authors.forEach(a::add);
        ArrayNode l = node.putArray("licenses");
        licenses.forEach(l::add);
        node.put("homepage", homepage);
        ObjectNode m = node.putObject("metadata");
        metadata.forEach(m::put);
        node.put("rubygemsVersion", rubygemsVersion);
        node.put("createdAt", createdAt == null ? null : createdAt.toString());
        node.put("yanked", yanked);
        node.put("yankedAt", yankedAt == null ? null : yankedAt.toString());
        node.put("pushedBy", pushedBy);
        return JSON.writeValueAsBytes(node);
    }

    /** Reads a record; {@link IllegalArgumentException} when the document is not one. */
    public static GemRecord fromJson(byte[] json) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("not a gem record: " + e.getOriginalMessage(), e);
        }
        if (node == null || !node.isObject() || text(node, "name") == null || text(node, "version") == null) {
            throw new IllegalArgumentException("not a gem record");
        }
        List<Dependency> deps = new ArrayList<>();
        for (JsonNode d : node.path("dependencies")) {
            String name = text(d, "name");
            if (name != null) {
                deps.add(new Dependency(name, text(d, "requirement"), text(d, "type")));
            }
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        node.path("metadata").properties().forEach(e -> {
            if (e.getValue().isString()) {
                metadata.put(e.getKey(), e.getValue().asString());
            }
        });
        return new GemRecord(text(node, "name"), text(node, "version"), text(node, "platform"), text(node, "sha256"),
                node.path("size").asLong(0), deps, text(node, "requiredRubyVersion"),
                text(node, "requiredRubygemsVersion"), text(node, "summary"), text(node, "description"),
                strings(node.path("authors")), strings(node.path("licenses")), text(node, "homepage"), metadata,
                text(node, "rubygemsVersion"), instant(text(node, "createdAt")), node.path("yanked").asBoolean(false),
                instant(text(node, "yankedAt")), text(node, "pushedBy"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode n : array) {
            if (n.isString()) {
                result.add(n.asString());
            }
        }
        return result;
    }

    private static Instant instant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
