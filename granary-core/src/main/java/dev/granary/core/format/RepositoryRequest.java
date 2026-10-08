package dev.granary.core.format;

import dev.granary.core.model.Repository;
import dev.granary.core.security.GranaryPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;

/**
 * A client request addressed to one repository.
 *
 * @param repository the target repository
 * @param path       the repository-relative path, URL-decoded, without a leading slash (may be empty)
 * @param http       the underlying servlet request (headers, method, body)
 * @param principal  the caller; never {@code null}, anonymous callers get an anonymous principal
 * @param baseUrl    the absolute, external URL of the repository root without a trailing slash,
 *                   e.g. {@code https://granary.example.com/repository/npm-all}
 */
public record RepositoryRequest(Repository repository, String path, HttpServletRequest http,
                                GranaryPrincipal principal, String baseUrl) {

    public String method() {
        return http.getMethod();
    }

    public boolean isGet() {
        return "GET".equals(method()) || "HEAD".equals(method());
    }

    public boolean isHead() {
        return "HEAD".equals(method());
    }

    public String header(String name) {
        return http.getHeader(name);
    }

    public String query(String name) {
        return http.getParameter(name);
    }

    public InputStream body() throws IOException {
        return http.getInputStream();
    }

    /** The absolute URL of the server root, derived from {@link #baseUrl}. */
    public String serverUrl() {
        int idx = baseUrl.indexOf("/repository/");
        return idx > 0 ? baseUrl.substring(0, idx) : baseUrl;
    }

    public RepositoryRequest withPath(String newPath) {
        return new RepositoryRequest(repository, newPath, http, principal, baseUrl);
    }
}
