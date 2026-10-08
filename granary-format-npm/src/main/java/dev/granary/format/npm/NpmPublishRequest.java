package dev.granary.format.npm;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The body of {@code PUT /{name}}: a packument fragment with the versions being published, the
 * dist-tags to set and, for a real publish, the tarballs inline as base64 {@code _attachments}.
 *
 * <p>The same request shape without attachments is what {@code npm deprecate} sends (the whole document
 * with {@code deprecated} members edited), so {@link #hasAttachments()} is what tells the two apart.
 *
 * @param name        the package name from the document
 * @param document    the document with {@code _attachments} removed
 * @param attachments decoded tarballs by attachment name
 */
public record NpmPublishRequest(String name, ObjectNode document, Map<String, byte[]> attachments) {

    public static NpmPublishRequest parse(ObjectMapper mapper, byte[] body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JacksonException e) {
            throw NpmError.badRequest("malformed publish document: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw NpmError.badRequest("publish document must be a JSON object");
        }
        ObjectNode doc = (ObjectNode) root;
        String name = NpmPackument.string(doc, NpmPackument.NAME);
        if (name == null) {
            name = NpmPackument.string(doc, NpmPackument.ID);
        }
        if (name == null || name.isBlank()) {
            throw NpmError.badRequest("publish document has no package name");
        }
        if (!NpmPath.isValidPackageName(name)) {
            throw NpmError.badRequest("invalid package name '" + name + "'");
        }
        Map<String, byte[]> attachments = new LinkedHashMap<>();
        JsonNode raw = doc.get("_attachments");
        if (raw != null && raw.isObject()) {
            for (Map.Entry<String, JsonNode> e : raw.properties()) {
                JsonNode data = e.getValue().isObject() ? e.getValue().get("data") : null;
                if (data == null || !data.isString()) {
                    throw NpmError.badRequest("attachment '" + e.getKey() + "' has no data");
                }
                try {
                    attachments.put(e.getKey(), Base64.getMimeDecoder().decode(data.stringValue()));
                } catch (IllegalArgumentException ex) {
                    throw NpmError.badRequest("attachment '" + e.getKey() + "' is not valid base64");
                }
            }
        }
        doc.remove("_attachments");
        return new NpmPublishRequest(name, doc, Map.copyOf(attachments));
    }

    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }

    /** The {@code versions} object, or an empty one; never {@code null}. */
    public ObjectNode versions() {
        return NpmPackument.versions(document);
    }

    public ObjectNode distTags() {
        return NpmPackument.distTags(document);
    }
}
