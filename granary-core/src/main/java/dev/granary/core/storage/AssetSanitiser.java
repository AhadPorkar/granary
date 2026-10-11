package dev.granary.core.storage;

import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/**
 * Makes values that come from outside (upstream response headers, client uploads) fit the {@code assets} columns
 * and stay usable as HTTP header values later. {@link StorageService} applies it to every asset it writes, so no
 * caller has to remember the column sizes.
 *
 * <ul>
 *   <li>A content type loses its control characters; when it is too long or does not parse, its parameters are
 *       dropped; when the bare {@code type/subtype} is still unusable, the type guessed from the path (and finally
 *       {@code application/octet-stream}) is used instead.</li>
 *   <li>An {@code ETag} or {@code Last-Modified} value that is too long or contains characters that cannot be sent
 *       back in a request header is dropped (stored as {@code null}), never truncated: a truncated validator would
 *       never match, and an absent one simply makes the next revalidation unconditional.</li>
 * </ul>
 */
public final class AssetSanitiser {

    /** Size of {@code assets.path}. */
    public static final int MAX_PATH_LENGTH = 2000;
    /** Size of {@code assets.content_type}. */
    public static final int MAX_CONTENT_TYPE_LENGTH = 200;
    /** Size of {@code assets.upstream_etag}. */
    public static final int MAX_ETAG_LENGTH = 500;
    /** Size of {@code assets.upstream_last_modified}. */
    public static final int MAX_LAST_MODIFIED_LENGTH = 100;
    /** Size of {@code components.namespace}. */
    public static final int MAX_NAMESPACE_LENGTH = 500;
    /** Size of {@code components.name}. */
    public static final int MAX_NAME_LENGTH = 500;
    /** Size of {@code components.version}. */
    public static final int MAX_VERSION_LENGTH = 200;
    /**
     * Most UTF-8 bytes of a path, and of a component's namespace, name and version together. Both are covered by a
     * unique index, and a PostgreSQL B-tree index entry cannot exceed 2704 bytes: 2,000 characters fit the column,
     * but not the index once most of them take three or four bytes. The margin covers the repository id and the
     * entry's own overhead.
     */
    public static final int MAX_INDEXED_BYTES = 2600;

    private AssetSanitiser() {
    }

    /**
     * A content type that fits the column and that {@link MediaType#parseMediaType} accepts as a concrete
     * {@code type/subtype}.
     *
     * @param value the declared content type, may be {@code null}
     * @param path  the asset path, used to guess a type when {@code value} is unusable
     */
    public static String contentType(String value, String path) {
        if (value != null) {
            String cleaned = withoutControlCharacters(value).trim();
            if (isPlausibleContentType(cleaned)) {
                return cleaned;
            }
            String bare = withoutParameters(cleaned);
            if (isPlausibleContentType(bare)) {
                return bare;
            }
        }
        String guessed = ContentTypes.guess(path);
        return isPlausibleContentType(guessed) ? guessed : ContentTypes.OCTET_STREAM;
    }

    /** The {@code ETag} to store, or {@code null} when it is absent, too long or not a valid header value. */
    public static String etag(String value) {
        return validator(value, MAX_ETAG_LENGTH);
    }

    /** The {@code Last-Modified} value to store, or {@code null} when it is absent, too long or not a valid header value. */
    public static String lastModified(String value) {
        return validator(value, MAX_LAST_MODIFIED_LENGTH);
    }

    /** Whether {@code value} is a concrete, parseable media type of printable ASCII that fits the column. */
    public static boolean isPlausibleContentType(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_CONTENT_TYPE_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        try {
            MediaType type = MediaType.parseMediaType(value);
            return !type.isWildcardType() && !type.isWildcardSubtype();
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    private static String validator(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = c == '\t' || (c >= 0x20 && c != 0x7F && c <= 0xFF);
            if (!allowed) {
                return null;
            }
        }
        return value;
    }

    /** Length of {@code value} encoded as UTF-8, without encoding it. */
    public static int utf8Length(CharSequence value) {
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    static String withoutControlCharacters(String value) {
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                if (out == null) {
                    out = new StringBuilder(value.length());
                    out.append(value, 0, i);
                }
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? value : out.toString();
    }

    private static String withoutParameters(String contentType) {
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
    }
}
