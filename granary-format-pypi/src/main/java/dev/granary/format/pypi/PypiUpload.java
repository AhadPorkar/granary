package dev.granary.format.pypi;

import dev.granary.core.format.RepositoryRequest;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

/**
 * One file upload as twine, uv, poetry, hatch and flit send it: a {@code multipart/form-data} POST with
 * {@code :action=file_upload}, the core metadata as form fields, the digests the client computed and the
 * file itself in the {@code content} field. The file is spooled to a temporary file while its digests are
 * computed, then validated against the fields before anything is stored.
 *
 * @param fields       every form field with all of its values (classifiers repeat)
 * @param file         the spooled upload; the caller deletes it
 * @param filename     the file name the client gave
 * @param size         the file size in bytes
 * @param sha256       hex SHA-256 of the bytes
 * @param md5          hex MD5 of the bytes
 * @param blake2b256   hex BLAKE2b-256 of the bytes
 * @param distribution what the file name says about the file
 */
public record PypiUpload(Map<String, List<String>> fields, Path file, String filename, long size, String sha256,
                         String md5, String blake2b256, PypiNames.Distribution distribution) {

    public static final String CONTENT_FIELD = "content";
    public static final String ACTION_FIELD = ":action";
    public static final String FILE_UPLOAD = "file_upload";

    public Optional<String> field(String name) {
        List<String> values = fields.get(name);
        return values == null || values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()
                ? Optional.empty()
                : Optional.of(values.getFirst().trim());
    }

    public String name() {
        return field("name").orElseThrow();
    }

    public String normalisedName() {
        return PypiNames.normalise(name());
    }

    public String version() {
        return field("version").orElseThrow();
    }

    /**
     * Reads and validates the upload. Throws {@link PypiError} (400) for anything the client got wrong;
     * the temporary file is removed again in that case.
     */
    public static PypiUpload read(RepositoryRequest request, Path tempDir) throws IOException {
        Files.createDirectories(tempDir);
        Path spool = Files.createTempFile(tempDir, "upload-", ".part");
        try {
            PypiUpload upload = readInto(request, spool);
            upload.validate();
            return upload;
        } catch (RuntimeException | IOException e) {
            Files.deleteIfExists(spool);
            throw e;
        }
    }

    private static PypiUpload readInto(RepositoryRequest request, Path spool) throws IOException {
        HttpServletRequest http = request.http();
        String contentType = http.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            throw PypiError.badRequest("Uploads must be multipart/form-data with the file in the 'content' field");
        }
        Map<String, List<String>> fields = new LinkedHashMap<>();
        String filename = null;
        Digests digests = null;
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(http, MultipartHttpServletRequest.class);
        if (multipart != null) {
            for (Map.Entry<String, String[]> e : multipart.getParameterMap().entrySet()) {
                fields.put(e.getKey(), new ArrayList<>(List.of(e.getValue())));
            }
            MultipartFile file = multipart.getFile(CONTENT_FIELD);
            if (file == null && !multipart.getFileMap().isEmpty()) {
                file = multipart.getFileMap().values().iterator().next();
            }
            if (file != null) {
                filename = file.getOriginalFilename();
                try (InputStream in = file.getInputStream()) {
                    digests = spool(in, spool);
                }
            }
        } else {
            Collection<Part> parts;
            try {
                parts = http.getParts();
            } catch (ServletException | IllegalStateException e) {
                throw PypiError.badRequest("The multipart upload cannot be read: " + e.getMessage());
            }
            for (Part part : parts) {
                if (part.getSubmittedFileName() == null && !CONTENT_FIELD.equals(part.getName())) {
                    try (InputStream in = part.getInputStream()) {
                        fields.computeIfAbsent(part.getName(), k -> new ArrayList<>())
                                .add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                } else if (digests == null && (CONTENT_FIELD.equals(part.getName()) || part.getSubmittedFileName() != null)) {
                    filename = part.getSubmittedFileName();
                    try (InputStream in = part.getInputStream()) {
                        digests = spool(in, spool);
                    }
                }
            }
        }
        if (digests == null) {
            throw PypiError.badRequest("The upload contains no file; send it in the 'content' field");
        }
        if (filename != null) {
            // some clients send a path; the file name is what counts
            filename = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1).trim();
        }
        if (filename == null || filename.isEmpty()) {
            throw PypiError.badRequest("The uploaded file has no file name");
        }
        String finalName = filename;
        PypiNames.Distribution distribution = PypiNames.parseFilename(finalName).orElseThrow(() ->
                PypiError.badRequest("'" + finalName + "' is not a valid wheel or sdist file name"));
        return new PypiUpload(fields, spool, finalName, digests.size, digests.sha256, digests.md5, digests.blake2b256,
                distribution);
    }

    private record Digests(long size, String sha256, String md5, String blake2b256) {
    }

    private static Digests spool(InputStream in, Path target) throws IOException {
        MessageDigest sha256;
        MessageDigest md5;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Blake2b blake = new Blake2b(32);
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
                sha256.update(buffer, 0, n);
                md5.update(buffer, 0, n);
                blake.update(buffer, 0, n);
                size += n;
            }
        }
        HexFormat hex = HexFormat.of();
        return new Digests(size, hex.formatHex(sha256.digest()), hex.formatHex(md5.digest()), hex.formatHex(blake.digest()));
    }

    private void validate() {
        String action = field(ACTION_FIELD).orElse(FILE_UPLOAD);
        if (!FILE_UPLOAD.equals(action)) {
            throw PypiError.badRequest("Unsupported action '" + action + "'; only file_upload is supported");
        }
        field("protocol_version").ifPresent(v -> {
            if (!v.equals("1")) {
                throw PypiError.badRequest("Unsupported protocol_version '" + v + "'");
            }
        });
        String name = field("name").orElseThrow(() -> PypiError.badRequest("The 'name' field is required"));
        String version = field("version").orElseThrow(() -> PypiError.badRequest("The 'version' field is required"));
        if (!PypiNames.isValidName(name)) {
            throw PypiError.badRequest("'" + name + "' is not a valid project name");
        }
        PypiVersion parsedVersion = PypiVersion.tryParse(version)
                .orElseThrow(() -> PypiError.badRequest("'" + version + "' is not a valid PEP 440 version"));
        PypiNames.DistributionType filetype = field("filetype")
                .map(f -> PypiNames.DistributionType.fromUploadName(f)
                        .orElseThrow(() -> PypiError.badRequest("Unsupported filetype '" + f + "'; only sdist and bdist_wheel are accepted")))
                .orElse(distribution.type());
        if (filetype != distribution.type()) {
            throw PypiError.badRequest("filetype '" + filetype.uploadName() + "' does not match the file name " + filename);
        }
        if (!distribution.normalisedName().equals(PypiNames.normalise(name))) {
            throw PypiError.badRequest("The file name " + filename + " does not belong to project '" + name + "'");
        }
        PypiVersion fileVersion = PypiVersion.tryParse(distribution.version())
                .orElseThrow(() -> PypiError.badRequest("The file name " + filename + " has no valid PEP 440 version"));
        if (fileVersion.compareTo(parsedVersion) != 0) {
            throw PypiError.badRequest("The file name " + filename + " does not match version '" + version + "'");
        }
        checkDigest("sha256_digest", sha256);
        checkDigest("md5_digest", md5);
        checkDigest("blake2_256_digest", blake2b256);
        if (size == 0) {
            throw PypiError.badRequest("The uploaded file is empty");
        }
    }

    private void checkDigest(String fieldName, String actual) {
        field(fieldName).ifPresent(claimed -> {
            if (!claimed.equalsIgnoreCase(actual)) {
                throw PypiError.badRequest("The " + fieldName + " of " + filename + " does not match the uploaded bytes");
            }
        });
    }
}
