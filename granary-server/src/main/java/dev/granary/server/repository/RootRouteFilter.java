package dev.granary.server.repository;

import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.FormatRegistry;
import dev.granary.core.format.RootRequest;
import dev.granary.core.format.RootRoute;
import dev.granary.core.model.Repository;
import dev.granary.core.repository.RepositoryService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/**
 * Lets formats whose clients require fixed root-level URLs (Docker's {@code /v2/…}, Terraform's
 * {@code /.well-known/terraform.json}) claim such requests: the first handler that returns a
 * {@link RootRoute} gets the request forwarded to {@code /repository/{name}/{path}}. Runs after the
 * security filters, so the caller is already authenticated when the dispatcher checks privileges.
 */
public class RootRouteFilter extends OncePerRequestFilter {

    private final FormatRegistry formats;
    private final RepositoryService repositories;

    public RootRouteFilter(FormatRegistry formats, RepositoryService repositories) {
        this.formats = formats;
        this.repositories = repositories;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = relativePath(request);
        return path.startsWith("api/") || path.startsWith("repository/") || path.startsWith("actuator/")
                || path.startsWith("assets/") || path.isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = relativePath(request);
        RootRequest rootRequest = new RootRequest(request.getMethod(), path, lookup());
        for (FormatHandler handler : formats.all()) {
            Optional<RootRoute> route = handler.routeRoot(rootRequest);
            if (route.isPresent()) {
                request.setAttribute(RootRoute.ATTRIBUTE, path);
                String target = "/repository/" + route.get().repositoryName() + "/" + encode(route.get().path());
                request.getRequestDispatcher(target).forward(request, response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private RootRequest.RepositoryLookup lookup() {
        return new RootRequest.RepositoryLookup() {
            @Override
            public Optional<Repository> find(String name) {
                return repositories.find(name);
            }

            @Override
            public List<Repository> ofFormat(String format) {
                return repositories.list().stream().filter(r -> r.getFormat().equals(format)).toList();
            }
        };
    }

    /** Decoded path relative to the context root, without a leading slash. */
    static String relativePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath() == null ? "" : request.getContextPath();
        if (uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        while (uri.startsWith("/")) {
            uri = uri.substring(1);
        }
        return UriUtils.decode(uri, StandardCharsets.UTF_8);
    }

    private static String encode(String path) {
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
