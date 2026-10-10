package dev.granary.format.swift;

import dev.granary.core.format.RepositoryRequest;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

/**
 * The body of a publish request ({@code PUT <scope>/<name>/<version>}): {@code multipart/form-data} with the
 * parts {@code source-archive} (required), {@code metadata}, {@code source-archive-signature} and
 * {@code metadata-signature}. SwiftPM sends every part without a file name and labels them
 * {@code Content-Transfer-Encoding: binary} (the metadata part {@code quoted-printable}, while its content is
 * plain JSON), so parts are read as raw bytes; a part explicitly labelled {@code base64}, as in the examples of
 * the specification, is decoded. As a convenience a bare {@code application/zip} body is accepted as the
 * archive alone.
 *
 * <p>The archive is spooled to a temporary file while its SHA-256 is computed; {@link #close()} removes it.
 *
 * @param archive           the spooled source archive
 * @param size              its size in bytes
 * @param sha256            its hex SHA-256, the release checksum
 * @param metadata          the metadata part, or {@code null}
 * @param signature         the source archive signature, or {@code null}
 * @param metadataSignature the metadata signature, or {@code null}
 */
public record SwiftUpload(Path archive, long size, String sha256, byte[] metadata, byte[] signature,
                          byte[] metadataSignature) implements AutoCloseable {

    public static final String SOURCE_ARCHIVE = "source-archive";
    public static final String METADATA = "metadata";
    public static final String SOURCE_ARCHIVE_SIGNATURE = "source-archive-signature";
    public static final String METADATA_SIGNATURE = "metadata-signature";

    /** Largest signature accepted (a CMS signature with its certificate chain is a few kilobytes). */
    static final int MAX_SIGNATURE_BYTES = 256 * 1024;

    private static final String CODE = "swift.publish.invalid";

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(archive);
    }

    /**
     * Reads the request body. Throws {@link SwiftProblem} (400, 413, 415, 422) for anything the client got wrong;
     * the temporary file is removed again in that case.
     *
     * @param maxArchiveBytes largest archive accepted, {@code <= 0} for no limit
     */
    public static SwiftUpload read(RepositoryRequest request, Path tempDir, long maxArchiveBytes) throws IOException {
        Files.createDirectories(tempDir);
        Path spool = Files.createTempFile(tempDir, "publish-", ".zip");
        try {
            return readInto(request, spool, maxArchiveBytes);
        } catch (RuntimeException | IOException e) {
            Files.deleteIfExists(spool);
            throw e;
        }
    }

    private static SwiftUpload readInto(RepositoryRequest request, Path spool, long maxArchiveBytes) throws IOException {
        HttpServletRequest http = request.http();
        String contentType = http.getContentType() == null ? "" : http.getContentType().toLowerCase(Locale.ROOT).trim();
        if (contentType.startsWith("application/zip") || contentType.startsWith("application/octet-stream")) {
            Spooled spooled;
            try (InputStream in = request.body()) {
                spooled = spool(in, spool, maxArchiveBytes);
            }
            return new SwiftUpload(spool, spooled.size(), spooled.sha256(), null, null, null);
        }
        if (!contentType.startsWith("multipart/")) {
            throw new SwiftProblem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, CODE,
                    "A publish request must be multipart/form-data with a '" + SOURCE_ARCHIVE + "' part");
        }
        Spooled archive = null;
        byte[] metadata = null;
        byte[] signature = null;
        byte[] metadataSignature = null;
        Collection<Part> parts = parts(http);
        if (!parts.isEmpty()) {
            for (Part part : parts) {
                String name = part.getName();
                if (name == null) {
                    continue;
                }
                switch (name) {
                    case SOURCE_ARCHIVE -> {
                        if (archive == null) {
                            try (InputStream in = decoded(part.getInputStream(), part.getHeader("Content-Transfer-Encoding"))) {
                                archive = spool(in, spool, maxArchiveBytes);
                            }
                        }
                    }
                    case METADATA -> metadata = metadata == null ? limited(part, SwiftMetadata.MAX_BYTES, false) : metadata;
                    case SOURCE_ARCHIVE_SIGNATURE -> signature = signature == null ? limited(part, MAX_SIGNATURE_BYTES, true) : signature;
                    case METADATA_SIGNATURE -> metadataSignature = metadataSignature == null
                            ? limited(part, MAX_SIGNATURE_BYTES, true) : metadataSignature;
                    default -> {
                        // unknown parts are ignored, as the protocol may grow new optional ones
                    }
                }
            }
        } else {
            MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(http, MultipartHttpServletRequest.class);
            if (multipart != null) {
                MultipartFile file = multipart.getFile(SOURCE_ARCHIVE);
                if (file != null) {
                    try (InputStream in = file.getInputStream()) {
                        archive = spool(in, spool, maxArchiveBytes);
                    }
                }
                metadata = fileOrParameter(multipart, METADATA, SwiftMetadata.MAX_BYTES);
                signature = fileOrParameter(multipart, SOURCE_ARCHIVE_SIGNATURE, MAX_SIGNATURE_BYTES);
                metadataSignature = fileOrParameter(multipart, METADATA_SIGNATURE, MAX_SIGNATURE_BYTES);
            }
        }
        if (archive == null) {
            throw SwiftProblem.badRequest(CODE, "The publish request has no '" + SOURCE_ARCHIVE + "' part");
        }
        return new SwiftUpload(spool, archive.size(), archive.sha256(), metadata, emptyToNull(signature),
                emptyToNull(metadataSignature));
    }

    private static Collection<Part> parts(HttpServletRequest http) {
        try {
            return http.getParts();
        } catch (ServletException | IllegalStateException | IOException | UnsupportedOperationException e) {
            throw SwiftProblem.badRequest(CODE, "The multipart body cannot be read: " + e.getMessage());
        }
    }

    private static byte[] fileOrParameter(MultipartHttpServletRequest multipart, String name, int limit) throws IOException {
        MultipartFile file = multipart.getFile(name);
        if (file != null) {
            if (file.getSize() > limit) {
                throw tooLarge(name, limit);
            }
            return file.getBytes();
        }
        String value = multipart.getParameter(name);
        if (value == null) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) {
            throw tooLarge(name, limit);
        }
        return bytes;
    }

    private static byte[] limited(Part part, int limit, boolean honourTransferEncoding) throws IOException {
        String encoding = honourTransferEncoding ? part.getHeader("Content-Transfer-Encoding") : null;
        try (InputStream in = decoded(part.getInputStream(), encoding)) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw tooLarge(part.getName(), limit);
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw SwiftProblem.badRequest(CODE, "The '" + part.getName() + "' part is not valid base64");
        }
    }

    /** Wraps base64 parts in a decoder; everything else (binary, 8bit, quoted-printable labels) is taken as is. */
    private static InputStream decoded(InputStream in, String transferEncoding) {
        if (transferEncoding != null && transferEncoding.trim().equalsIgnoreCase("base64")) {
            return new Base64Part(in);
        }
        return in;
    }

    /** An I/O failure of the part itself, as opposed to a decoding failure raised by the base64 decoder. */
    private static final class SourceFailure extends IOException {

        SourceFailure(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    /**
     * A base64 decoding stream that reports malformed content as {@link IllegalArgumentException} (turned into a
     * 400 by the callers) while real I/O failures of the request body stay {@link IOException}s.
     */
    private static final class Base64Part extends FilterInputStream {

        Base64Part(InputStream raw) {
            super(Base64.getMimeDecoder().wrap(new FilterInputStream(raw) {
                @Override
                public int read() throws IOException {
                    try {
                        return super.read();
                    } catch (IOException e) {
                        throw new SourceFailure(e);
                    }
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    try {
                        return super.read(b, off, len);
                    } catch (IOException e) {
                        throw new SourceFailure(e);
                    }
                }
            }));
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } catch (SourceFailure e) {
                throw (IOException) e.getCause();
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return super.read(b, off, len);
            } catch (SourceFailure e) {
                throw (IOException) e.getCause();
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }
    }

    private static byte[] emptyToNull(byte[] bytes) {
        return bytes == null || bytes.length == 0 ? null : bytes;
    }

    private record Spooled(long size, String sha256) {
    }

    private static Spooled spool(InputStream in, Path target, long maxBytes) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                size += n;
                if (maxBytes > 0 && size > maxBytes) {
                    throw tooLarge(SOURCE_ARCHIVE, maxBytes);
                }
                out.write(buffer, 0, n);
                digest.update(buffer, 0, n);
            }
        } catch (IllegalArgumentException e) {
            throw SwiftProblem.badRequest(CODE, "The '" + SOURCE_ARCHIVE + "' part is not valid base64");
        }
        if (size == 0) {
            throw SwiftProblem.unprocessable("swift.archive.invalid", "The source archive is empty");
        }
        return new Spooled(size, HexFormat.of().formatHex(digest.digest()));
    }

    private static SwiftProblem tooLarge(String part, long limit) {
        return new SwiftProblem(HttpStatus.CONTENT_TOO_LARGE, CODE, "The '" + part + "' part may not exceed " + limit + " bytes");
    }
}
