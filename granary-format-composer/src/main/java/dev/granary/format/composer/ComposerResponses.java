package dev.granary.format.composer;

import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.storage.Checksums;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** HTTP responses in the shapes Composer expects. */
public final class ComposerResponses {

    private ComposerResponses() {
    }

    /**
     * A generated JSON document. The ETag is the digest of the bytes. When {@code lastModified} is known it is sent as
     * {@code Last-Modified}; Composer stores it and sends it back as {@code If-Modified-Since}, and only an exact
     * match yields 304, so a document whose sources moved back in time (a member removed from a group) is still
     * sent again.
     */
    public static ResponseEntity<?> json(RepositoryRequest request, byte[] body, Instant lastModified) {
        return json(request, body, lastModified, Instant.now());
    }

    /**
     * {@link #json(RepositoryRequest, byte[], Instant)} at the instant {@code now}. A {@code Last-Modified} in the
     * current second is a weak validator (RFC 9110, section 8.8.2.2): the document can still change within that
     * second, and the {@code If-Modified-Since} Composer would send back could then never tell the two versions
     * apart, so it is neither sent nor matched until the second is over.
     */
    static ResponseEntity<?> json(RepositoryRequest request, byte[] body, Instant lastModified, Instant now) {
        Instant validator = lastModified != null && lastModified.getEpochSecond() < now.getEpochSecond() ? lastModified : null;
        String etag = "\"" + Checksums.hex("SHA-256", body) + "\"";
        String ifNoneMatch = request.header(HttpHeaders.IF_NONE_MATCH);
        boolean notModified;
        if (ifNoneMatch != null) {
            notModified = ifNoneMatch.contains(etag) || ifNoneMatch.trim().equals("*");
        } else {
            notModified = validator != null && sameSecond(request.header(HttpHeaders.IF_MODIFIED_SINCE), validator);
        }
        if (notModified) {
            ResponseEntity.HeadersBuilder<?> builder = ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag);
            if (validator != null) {
                builder.lastModified(validator);
            }
            return builder.build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length);
        if (validator != null) {
            builder.lastModified(validator);
        }
        return request.isHead() ? builder.build() : builder.body(body);
    }

    /** Whether an {@code If-Modified-Since} value names exactly the second {@code lastModified} falls in. */
    static boolean sameSecond(String header, Instant lastModified) {
        if (header == null || header.isBlank()) {
            return false;
        }
        try {
            Instant since = ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return since.getEpochSecond() == lastModified.getEpochSecond();
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public static ResponseEntity<?> error(ComposerError error) {
        return ContentResponses.error(error.status(), error.code(), error.getMessage());
    }

    /** {@code 201 Created} with a small JSON body describing what was stored. */
    public static ResponseEntity<?> created(byte[] body, String location) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON);
        if (location != null) {
            builder.header(HttpHeaders.LOCATION, location);
        }
        return builder.contentLength(body.length).body(body);
    }
}
