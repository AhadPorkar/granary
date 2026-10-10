package dev.granary.format.go;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code .info} document of a module version: {@code {"Version":"v1.2.3","Time":"2020-01-01T00:00:00Z"}}.
 * Upstream documents may carry more (proxy.golang.org adds {@code Origin}); those are passed through untouched,
 * this class only describes what Granary generates and what it validates on upload.
 *
 * @param version the canonical version
 * @param time    when the version was created, or {@code null} when unknown
 */
public record GoInfo(String version, Instant time) {

    public static final String CONTENT_TYPE = "application/json";

    public static GoInfo now(String version) {
        return new GoInfo(version, Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    public byte[] toJson(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("Version", version);
        if (time != null) {
            node.put("Time", DateTimeFormatter.ISO_INSTANT.format(time.truncatedTo(ChronoUnit.SECONDS)));
        }
        return mapper.writeValueAsBytes(node);
    }

    /** Reads a document produced by a proxy or a client; the version is required, the time optional. */
    public static Optional<GoInfo> parse(ObjectMapper mapper, byte[] json) {
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        JsonNode version = node.get("Version");
        if (version == null || !version.isString() || version.stringValue().isBlank()) {
            return Optional.empty();
        }
        Instant time = null;
        JsonNode t = node.get("Time");
        if (t != null && t.isString() && !t.stringValue().isBlank()) {
            try {
                time = Instant.parse(t.stringValue());
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        }
        return Optional.of(new GoInfo(version.stringValue().trim(), time));
    }

    /**
     * Validates a client-supplied {@code .info} and returns it normalised: the version must match the one in the
     * URL and the time, if given, must be RFC 3339.
     */
    public static byte[] validateUpload(ObjectMapper mapper, byte[] body, String expectedVersion) {
        GoInfo info = parse(mapper, body).orElseThrow(() -> GoError.badRequest(
                "the .info document must be a JSON object with a \"Version\" string and an optional RFC 3339 \"Time\""));
        if (!info.version().equals(expectedVersion)) {
            throw GoError.badRequest("the .info document names version " + info.version() + " but the URL names " + expectedVersion);
        }
        return info.toJson(mapper);
    }

    public String toJsonString(ObjectMapper mapper) {
        return new String(toJson(mapper), StandardCharsets.UTF_8);
    }
}
