package dev.granary.server.security;

import dev.granary.core.security.ApiTokenService;
import dev.granary.core.security.AuthenticationService;
import dev.granary.core.security.GranaryPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts every credential shape a package manager or browser may send:
 * <ul>
 *   <li>{@code Authorization: Basic} with username + password or username + API token (Maven, NuGet, npm {@code _auth})</li>
 *   <li>{@code Authorization: Bearer} with an API token (npm {@code _authToken}) or a web-session JWT</li>
 *   <li>{@code X-NuGet-ApiKey} with an API token ({@code dotnet nuget push})</li>
 * </ul>
 * Valid credentials populate the security context. Invalid ones are answered with 401 right away so a
 * misconfigured client sees the real reason. No credentials at all leaves the request anonymous; whether
 * that is enough is decided later by the endpoint.
 */
@Component
public class CredentialsFilter extends OncePerRequestFilter {

    private final AuthenticationService authentication;
    private final JwtService jwt;

    public CredentialsFilter(AuthenticationService authentication, JwtService jwt) {
        this.authentication = authentication;
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<GranaryPrincipal> principal;
        try {
            principal = resolve(request);
        } catch (BadCredentials e) {
            SecurityResponses.unauthorized(request, response, e.getMessage());
            return;
        }
        if (principal.isPresent()) {
            SecurityContextHolder.getContext().setAuthentication(new GranaryAuthentication(principal.get()));
        } else {
            SecurityContextHolder.getContext().setAuthentication(new GranaryAuthentication(authentication.anonymous()));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<GranaryPrincipal> resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && !header.isBlank()) {
            String[] parts = header.trim().split("\\s+", 2);
            if (parts.length == 2 && parts[0].equalsIgnoreCase("Basic")) {
                return basic(parts[1]);
            }
            if (parts.length == 2 && parts[0].equalsIgnoreCase("Bearer")) {
                return bearer(parts[1].trim());
            }
            if (parts.length == 2 && parts[0].equalsIgnoreCase("Token")) {
                return apiToken(parts[1].trim(), "Invalid or expired API token");
            }
            // cargo, gem push, mix hex.publish and the Conan client send the bare key as the whole header
            if (parts.length == 1 && ApiTokenService.looksLikeToken(parts[0])) {
                return apiToken(parts[0], "Invalid or expired API token");
            }
            throw new BadCredentials("Unsupported Authorization scheme");
        }
        for (String keyHeader : API_KEY_HEADERS) {
            String key = request.getHeader(keyHeader);
            if (key != null && !key.isBlank()) {
                return apiToken(key.trim(), "Invalid " + keyHeader + " value");
            }
        }
        return Optional.empty();
    }

    /** Headers package managers use to carry an API key instead of {@code Authorization}. */
    private static final java.util.List<String> API_KEY_HEADERS = java.util.List.of("X-NuGet-ApiKey", "X-Api-Key");

    private Optional<GranaryPrincipal> apiToken(String secret, String failure) {
        return Optional.of(authentication.authenticateToken(secret)
                .orElseThrow(() -> new BadCredentials(failure)));
    }

    private Optional<GranaryPrincipal> basic(String encoded) {
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadCredentials("Malformed Basic credentials");
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            throw new BadCredentials("Malformed Basic credentials");
        }
        String username = decoded.substring(0, colon);
        String password = decoded.substring(colon + 1);
        return Optional.of(authentication.authenticate(username, password)
                .orElseThrow(() -> new BadCredentials("Invalid username or password")));
    }

    private Optional<GranaryPrincipal> bearer(String token) {
        if (ApiTokenService.looksLikeToken(token)) {
            return Optional.of(authentication.authenticateToken(token)
                    .orElseThrow(() -> new BadCredentials("Invalid or expired API token")));
        }
        Optional<String> subject = jwt.subjectOf(token);
        if (subject.isEmpty()) {
            throw new BadCredentials("Invalid or expired session");
        }
        return Optional.of(authentication.principalFor(subject.get())
                .orElseThrow(() -> new BadCredentials("User no longer exists or is disabled")));
    }

    static final class BadCredentials extends RuntimeException {
        BadCredentials(String message) {
            super(message);
        }
    }
}
