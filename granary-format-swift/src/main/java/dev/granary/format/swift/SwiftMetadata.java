package dev.granary.format.swift;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Package release metadata as publishers submit it (the {@code metadata} part of a publish request, normally
 * {@code package-metadata.json}). The protocol's JSON schema is checked for the members SwiftPM decodes, because
 * SwiftPM rejects a whole release metadata document when one of them has the wrong type; members the schema does
 * not define are kept as they are. {@code originalPublicationTime} is rewritten to whole seconds in UTC, the
 * ISO 8601 form every SwiftPM version decodes.
 */
public final class SwiftMetadata {

    /** Largest metadata document accepted. */
    public static final int MAX_BYTES = 1024 * 1024;

    private static final String CODE = "swift.metadata.invalid";

    private SwiftMetadata() {
    }

    /** Parses and validates the metadata part; {@code null} or blank bytes mean "no metadata" (an empty object). */
    public static ObjectNode parse(ObjectMapper json, byte[] bytes) {
        if (bytes == null || new String(bytes, StandardCharsets.UTF_8).isBlank()) {
            return json.createObjectNode();
        }
        JsonNode node;
        try {
            node = json.readTree(bytes);
        } catch (JacksonException e) {
            throw SwiftProblem.unprocessable(CODE, "invalid JSON provided for release metadata: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            throw SwiftProblem.unprocessable(CODE, "release metadata must be a JSON object");
        }
        ObjectNode metadata = (ObjectNode) node;
        validateAuthor(metadata.get("author"));
        for (String key : List.of("description", "licenseURL", "readmeURL")) {
            optionalString(metadata, key, key);
        }
        JsonNode urls = metadata.get("repositoryURLs");
        if (urls != null && !urls.isNull()) {
            if (!urls.isArray()) {
                throw SwiftProblem.unprocessable(CODE, "repositoryURLs must be an array of strings");
            }
            for (JsonNode url : urls) {
                if (!url.isString()) {
                    throw SwiftProblem.unprocessable(CODE, "repositoryURLs must be an array of strings");
                }
            }
        }
        JsonNode time = metadata.get("originalPublicationTime");
        if (time != null && !time.isNull()) {
            if (!time.isString()) {
                throw SwiftProblem.unprocessable(CODE, "originalPublicationTime must be an ISO 8601 date-time string");
            }
            metadata.put("originalPublicationTime", normaliseTime(time.stringValue())
                    .orElseThrow(() -> SwiftProblem.unprocessable(CODE, "originalPublicationTime '" + time.stringValue()
                            + "' is not an ISO 8601 date-time such as 2024-01-31T12:00:00Z")));
        }
        return metadata;
    }

    private static void validateAuthor(JsonNode author) {
        if (author == null || author.isNull()) {
            return;
        }
        if (!author.isObject()) {
            throw SwiftProblem.unprocessable(CODE, "author must be an object with at least a name");
        }
        requiredString(author, "name", "author.name");
        for (String key : List.of("email", "description", "url")) {
            optionalString(author, key, "author." + key);
        }
        JsonNode organization = author.get("organization");
        if (organization != null && !organization.isNull()) {
            if (!organization.isObject()) {
                throw SwiftProblem.unprocessable(CODE, "author.organization must be an object with at least a name");
            }
            requiredString(organization, "name", "author.organization.name");
            for (String key : List.of("email", "description", "url")) {
                optionalString(organization, key, "author.organization." + key);
            }
        }
    }

    private static void requiredString(JsonNode parent, String key, String label) {
        JsonNode value = parent.get(key);
        if (value == null || !value.isString()) {
            throw SwiftProblem.unprocessable(CODE, label + " is required and must be a string");
        }
    }

    private static void optionalString(JsonNode parent, String key, String label) {
        JsonNode value = parent.get(key);
        if (value != null && !value.isNull() && !value.isString()) {
            throw SwiftProblem.unprocessable(CODE, label + " must be a string");
        }
    }

    /** An ISO 8601 date-time with offset, as {@code yyyy-MM-ddTHH:mm:ssZ} in UTC. */
    static java.util.Optional<String> normaliseTime(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            return java.util.Optional.empty();
        }
        Instant instant;
        try {
            instant = OffsetDateTime.parse(value.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(timestamp(instant));
    }

    /** {@code 2024-01-31T12:00:00Z}: whole seconds, UTC. */
    public static String timestamp(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    /** The {@code repositoryURLs} of a metadata object, strings only. */
    public static List<String> repositoryUrls(JsonNode metadata) {
        List<String> urls = new ArrayList<>();
        if (metadata == null) {
            return urls;
        }
        for (JsonNode url : metadata.path("repositoryURLs")) {
            if (url.isString() && !url.stringValue().isBlank()) {
                urls.add(url.stringValue().trim());
            }
        }
        return urls;
    }
}
