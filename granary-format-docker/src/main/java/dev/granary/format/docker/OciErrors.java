package dev.granary.format.docker;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Renders errors in the shape the distribution specification defines and every client knows how to print. */
public final class OciErrors {

    public static final String BASIC_CHALLENGE = "Basic realm=\"Granary\"";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private OciErrors() {
    }

    public static byte[] body(OciErrorCode code, String message, Map<String, Object> detail) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode errors = root.putArray("errors");
        ObjectNode error = errors.addObject();
        error.put("code", code.name());
        error.put("message", message == null ? code.defaultMessage() : message);
        if (detail == null) {
            error.putNull("detail");
        } else {
            error.set("detail", MAPPER.valueToTree(detail));
        }
        return MAPPER.writeValueAsBytes(root);
    }

    public static ResponseEntity<?> response(HttpStatus status, OciErrorCode code, String message, Map<String, Object> detail) {
        byte[] body = body(code, message, detail);
        ResponseEntity.BodyBuilder builder = DockerResponses.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length);
        if (status == HttpStatus.UNAUTHORIZED) {
            builder.header("WWW-Authenticate", BASIC_CHALLENGE);
        }
        return builder.body(body);
    }

    public static ResponseEntity<?> response(DockerError error) {
        return response(error.status(), error.code(), error.getMessage(), error.detail());
    }
}
