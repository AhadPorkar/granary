package dev.granary.server.repository;

import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.FormatRegistry;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.repository.RepositoryService;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.settings.SettingsService;
import dev.granary.core.storage.StorageService;
import dev.granary.server.security.CurrentUser;
import dev.granary.server.security.SecurityResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * The single entry point for package managers: {@code /repository/{name}/…}. Looks the repository up,
 * enforces read/write privileges and hands the request to the handler of the repository's format.
 */
@RestController
public class RepositoryDispatchController {

    private final RepositoryService repositories;
    private final FormatRegistry formats;
    private final SettingsService settings;

    public RepositoryDispatchController(RepositoryService repositories, FormatRegistry formats, SettingsService settings) {
        this.repositories = repositories;
        this.formats = formats;
        this.settings = settings;
    }

    @RequestMapping({"/repository/{name}", "/repository/{name}/", "/repository/{name}/**"})
    public ResponseEntity<?> dispatch(@PathVariable String name, HttpServletRequest http, HttpServletResponse response)
            throws IOException {
        Optional<Repository> found = repositories.find(name);
        if (found.isEmpty()) {
            return json(HttpStatus.NOT_FOUND, "repository.notFound", "Repository '" + name + "' does not exist");
        }
        Repository repo = found.get();
        String path;
        try {
            path = StorageService.normalise(relativePath(http, name));
        } catch (IllegalArgumentException e) {
            return json(HttpStatus.BAD_REQUEST, "path.invalid", "Illegal path");
        }
        FormatHandler handler = formats.require(repo.getFormat());
        GranaryPrincipal principal = CurrentUser.principal();
        RepositoryRequest request = new RepositoryRequest(repo, path, http, principal, baseUrl(http, name));

        AccessLevel access = handler.requiredAccess(request);
        boolean allowed = switch (access) {
            case NONE -> true;
            case READ -> principal.canRead(name);
            case WRITE -> principal.canWrite(name);
        };
        if (!allowed) {
            if (principal.anonymous()) {
                SecurityResponses.unauthorized(http, response, "Authentication required for " + access.name().toLowerCase()
                        + " access to repository '" + name + "'");
            } else {
                SecurityResponses.forbidden(response, "User '" + principal.username() + "' may not "
                        + access.name().toLowerCase() + " repository '" + name + "'");
            }
            return null;
        }
        if (!repo.isOnline() && access != AccessLevel.NONE) {
            return json(HttpStatus.SERVICE_UNAVAILABLE, "repository.offline", "Repository '" + name + "' is offline");
        }
        return handler.handle(request);
    }

    /** The decoded path after {@code /repository/<name>}, never starting with a slash. */
    static String relativePath(HttpServletRequest http, String name) {
        String uri = http.getRequestURI();
        String context = http.getContextPath() == null ? "" : http.getContextPath();
        if (uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        String prefix = "/repository/" + name;
        String rest = uri.startsWith(prefix) ? uri.substring(prefix.length()) : "";
        while (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        return UriUtils.decode(rest, StandardCharsets.UTF_8);
    }

    private String baseUrl(HttpServletRequest http, String name) {
        String root = settings.baseUrl().orElseGet(() ->
                ServletUriComponentsBuilder.fromContextPath(http).build().toUriString());
        return root + "/repository/" + name;
    }

    private static ResponseEntity<?> json(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", code, "message", message));
    }
}
