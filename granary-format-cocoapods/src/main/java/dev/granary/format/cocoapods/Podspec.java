package dev.granary.format.cocoapods;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A JSON podspec ({@code pod ipc spec Foo.podspec > Foo.podspec.json}). Only the root attributes Granary needs are
 * interpreted: {@code name}, {@code version}, {@code source} and the deprecation flags; everything else passes
 * through untouched. Numbers keep their exact spelling ({@code "swift_version": 4.10} stays {@code 4.10}), so
 * rewriting a podspec never changes what it says.
 */
public final class Podspec {

    /** Decimal numbers stay {@code BigDecimal} with their trailing zeros; property order is kept. */
    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    /**
     * The layout of {@code pod ipc spec} and the CDN's podspecs (Ruby's {@code JSON.pretty_generate}): two-space
     * indent, {@code "key": value}, one array element per line, {@code []} and {@code {}} for empty containers.
     */
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter(Separators.createDefaultInstance()
            .withObjectNameValueSpacing(Separators.Spacing.AFTER)
            .withObjectEmptySeparator("")
            .withArrayEmptySeparator(""))
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    public static final String CONTENT_TYPE = "application/json";

    private final ObjectNode root;

    private Podspec(ObjectNode root) {
        this.root = root;
    }

    /** Parses a podspec; a body that is not a JSON object is a {@link CocoaPodsError#invalidPodspec}. */
    public static Podspec parse(byte[] json) {
        return tryParse(json).orElseThrow(() -> CocoaPodsError.invalidPodspec(
                "The podspec is not a JSON object; convert a Ruby podspec with: pod ipc spec Foo.podspec > Foo.podspec.json"));
    }

    public static Optional<Podspec> tryParse(byte[] json) {
        if (json == null || json.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node != null && node.isObject() ? Optional.of(new Podspec((ObjectNode) node)) : Optional.empty();
        } catch (JacksonException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public ObjectNode node() {
        return root;
    }

    public Optional<String> name() {
        return string(root, "name");
    }

    public Optional<String> version() {
        return string(root, "version");
    }

    /** The {@code source} object, if the podspec has one. */
    public Optional<ObjectNode> source() {
        JsonNode source = root.get("source");
        return source != null && source.isObject() ? Optional.of((ObjectNode) source) : Optional.empty();
    }

    /** {@code source.http}, the URL of the source archive, when the pod is distributed that way. */
    public Optional<String> httpSource() {
        return source().flatMap(s -> string(s, "http"));
    }

    /** {@code source.type}, the explicit archive type, if set. */
    public Optional<String> sourceType() {
        return source().flatMap(s -> string(s, "type"));
    }

    /** {@code source.headers}: extra request headers ({@code "Name: value"}) the archive download needs. */
    public List<String> sourceHeaders() {
        List<String> headers = new ArrayList<>();
        source().map(s -> s.get("headers")).ifPresent(h -> {
            if (h.isArray()) {
                h.forEach(item -> {
                    if (item.isString()) {
                        headers.add(item.asString());
                    }
                });
            } else if (h.isString()) {
                headers.add(h.asString());
            }
        });
        return headers;
    }

    /** {@code pod trunk deprecate} sets {@code deprecated} or {@code deprecated_in_favor_of}. */
    public boolean isDeprecated() {
        JsonNode deprecated = root.get("deprecated");
        if (deprecated != null && deprecated.isBoolean() && deprecated.asBoolean()) {
            return true;
        }
        return string(root, "deprecated_in_favor_of").filter(s -> !s.isBlank()).isPresent();
    }

    /**
     * A copy whose {@code source.http} is {@code url}. The other source keys ({@code type}, {@code flatten},
     * {@code sha256}, {@code sha1}) stay; {@code headers} are dropped because they were meant for the original host
     * (Granary sends them there itself) and would otherwise reach Granary.
     */
    public Podspec withHttpSource(String url) {
        ObjectNode copy = root.deepCopy();
        ObjectNode source = (ObjectNode) copy.get("source");
        source.put("http", url);
        source.remove("headers");
        return new Podspec(copy);
    }

    /**
     * A copy whose source is an uploaded archive: {@code http} and {@code sha256} are set, {@code type} is kept or
     * set when the file name does not imply it, {@code flatten} is kept, every other source key (git, tag, …) goes.
     */
    public Podspec withUploadedSource(String url, SourceArchive.Type type, String sha256, boolean typeImpliedByName) {
        ObjectNode copy = root.deepCopy();
        JsonNode previous = copy.get("source");
        ObjectNode source = MAPPER.createObjectNode();
        source.put("http", url);
        if (!typeImpliedByName) {
            source.put("type", type.option());
        }
        if (previous != null && previous.isObject() && previous.has("flatten")) {
            source.set("flatten", previous.get("flatten"));
        }
        source.put("sha256", sha256);
        copy.set("source", source);
        return new Podspec(copy);
    }

    /** Pretty-printed JSON, laid out like the files on the CDN. */
    public byte[] toBytes() {
        return MAPPER.writer().with(PRETTY).writeValueAsBytes(root);
    }

    private static Optional<String> string(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? Optional.of(value.asString()) : Optional.empty();
    }
}
