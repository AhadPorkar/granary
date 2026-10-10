package dev.granary.format.helm;

import dev.granary.core.format.RepositoryRequest;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

/**
 * The files of an upload request, spooled to temporary files: the {@code chart} and {@code prov} fields of a
 * multipart form (what {@code helm cm-push} and {@code curl -F} send) or the raw request body (what
 * {@code curl --data-binary} and {@code PUT} uploads send). Close it to delete the temporary files.
 *
 * @param chart the chart archive, or {@code null}
 * @param prov  the provenance file, or {@code null}
 */
record HelmUpload(Path chart, Path prov) implements AutoCloseable {

    enum Body { CHART, PROV }

    static final String CHART_FIELD = "chart";
    static final String PROV_FIELD = "prov";

    /**
     * Reads the request.
     *
     * @param rawBody  what a non-multipart body is
     * @param maxBytes the largest file accepted, or 0 for no limit
     */
    static HelmUpload read(RepositoryRequest request, Path tempDir, Body rawBody, long maxBytes) throws IOException {
        Files.createDirectories(tempDir);
        HttpServletRequest http = request.http();
        List<Path> created = new ArrayList<>();
        try {
            MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(http, MultipartHttpServletRequest.class);
            if (multipart != null) {
                HelmUpload upload = fromMultipart(multipart, tempDir, maxBytes, created);
                created.clear();
                return upload;
            }
            String contentType = http.getContentType();
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
                HelmUpload upload = fromParts(http, tempDir, maxBytes, created);
                created.clear();
                return upload;
            }
            Path file = spool(request.body(), tempDir, maxBytes, created);
            if (Files.size(file) == 0) {
                throw HelmError.badRequest(rawBody == Body.CHART ? "the request contains no chart" : "the request contains no provenance file");
            }
            created.clear();
            return rawBody == Body.CHART ? new HelmUpload(file, null) : new HelmUpload(null, file);
        } finally {
            for (Path p : created) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static HelmUpload fromMultipart(MultipartHttpServletRequest multipart, Path tempDir, long maxBytes, List<Path> created)
            throws IOException {
        MultipartFile chart = multipart.getFile(CHART_FIELD);
        MultipartFile prov = multipart.getFile(PROV_FIELD);
        if (chart == null && prov == null) {
            // a single file under another field name: decide by its file name
            Collection<MultipartFile> files = multipart.getFileMap().values();
            if (files.size() == 1) {
                MultipartFile only = files.iterator().next();
                String name = only.getOriginalFilename() == null ? "" : only.getOriginalFilename();
                if (name.endsWith(HelmPath.PROV_SUFFIX)) {
                    prov = only;
                } else {
                    chart = only;
                }
            }
        }
        Path chartFile = null;
        Path provFile = null;
        if (chart != null) {
            try (InputStream in = chart.getInputStream()) {
                chartFile = spool(in, tempDir, maxBytes, created);
            }
        }
        if (prov != null) {
            try (InputStream in = prov.getInputStream()) {
                provFile = spool(in, tempDir, maxBytes, created);
            }
        }
        return validate(chartFile, provFile);
    }

    private static HelmUpload fromParts(HttpServletRequest http, Path tempDir, long maxBytes, List<Path> created) throws IOException {
        Collection<Part> parts;
        try {
            parts = http.getParts();
        } catch (ServletException | IllegalStateException e) {
            throw HelmError.badRequest("the multipart upload cannot be read: " + e.getMessage());
        }
        Part chart = null;
        Part prov = null;
        List<Part> files = new ArrayList<>();
        for (Part part : parts) {
            if (CHART_FIELD.equals(part.getName())) {
                chart = part;
            } else if (PROV_FIELD.equals(part.getName())) {
                prov = part;
            } else if (part.getSubmittedFileName() != null) {
                files.add(part);
            }
        }
        if (chart == null && prov == null && files.size() == 1) {
            Part only = files.getFirst();
            if (only.getSubmittedFileName().endsWith(HelmPath.PROV_SUFFIX)) {
                prov = only;
            } else {
                chart = only;
            }
        }
        Path chartFile = null;
        Path provFile = null;
        if (chart != null) {
            try (InputStream in = chart.getInputStream()) {
                chartFile = spool(in, tempDir, maxBytes, created);
            }
        }
        if (prov != null) {
            try (InputStream in = prov.getInputStream()) {
                provFile = spool(in, tempDir, maxBytes, created);
            }
        }
        return validate(chartFile, provFile);
    }

    private static HelmUpload validate(Path chart, Path prov) throws IOException {
        if (chart != null && Files.size(chart) == 0) {
            throw HelmError.badRequest("the chart field is empty");
        }
        if (prov != null && Files.size(prov) == 0) {
            throw HelmError.badRequest("the prov field is empty");
        }
        if (chart == null && prov == null) {
            throw HelmError.badRequest("the multipart upload has neither a 'chart' nor a 'prov' field");
        }
        return new HelmUpload(chart, prov);
    }

    private static Path spool(InputStream in, Path tempDir, long maxBytes, List<Path> created) throws IOException {
        Path file = Files.createTempFile(tempDir, "upload-", ".tmp");
        created.add(file);
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (maxBytes > 0 && total > maxBytes) {
                    throw HelmError.tooLarge("the upload exceeds the limit of " + (maxBytes / (1024 * 1024)) + " MiB of this repository");
                }
                out.write(buffer, 0, n);
            }
        }
        return file;
    }

    @Override
    public void close() throws IOException {
        if (chart != null) {
            Files.deleteIfExists(chart);
        }
        if (prov != null) {
            Files.deleteIfExists(prov);
        }
    }
}
