package dev.granary.format.cargo;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one JSON mapper of the format. Index lines must come out compact, in insertion order and byte-for-byte
 * reproducible, so the format does not depend on how the application's shared mapper happens to be configured.
 */
final class CargoJson {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private CargoJson() {
    }

    static ObjectNode object() {
        return NODES.objectNode();
    }

    /** Parses a JSON object; anything else (malformed text, an array, a scalar) is empty. */
    static Optional<ObjectNode> parseObject(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            return node != null && node.isObject() ? Optional.of((ObjectNode) node) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    static Optional<ObjectNode> parseObject(byte[] bytes) {
        return parseObject(new String(bytes, StandardCharsets.UTF_8));
    }

    static String write(JsonNode node) {
        return MAPPER.writeValueAsString(node);
    }

    static byte[] bytes(JsonNode node) {
        return MAPPER.writeValueAsBytes(node);
    }

    /** The string at {@code field}, or {@code null} when it is missing, {@code null} or not a string. */
    static String string(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
