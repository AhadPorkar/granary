package dev.granary.format.rubygems;

import static dev.granary.format.rubygems.RubygemsTestSupport.baseUrl;
import static dev.granary.format.rubygems.RubygemsTestSupport.user;
import static org.mockito.Mockito.mock;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.ApiTokenService;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.security.UserService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Wires the handler the way Spring does, over {@link FakeStorage} and mocked proxy, group, user and token services. */
abstract class RubygemsHandlerTestBase {

    @TempDir
    Path temp;

    final FakeStorage storage = new FakeStorage();
    final ProxyService proxyService = mock(ProxyService.class);
    final GroupResolver groups = mock(GroupResolver.class);
    final UserService users = mock(UserService.class);
    final ApiTokenService tokens = mock(ApiTokenService.class);
    final UpstreamHealth health = new UpstreamHealth(new GranaryProperties());
    final NegativeCache negativeCache = new NegativeCache();

    DerivedDocuments derived;
    RubygemsHosted hosted;
    RubygemsIndexes indexes;
    RubygemsFormatHandler handler;

    @BeforeEach
    void wire() {
        derived = new DerivedDocuments(storage.mock, temp.resolve("tmp"));
        hosted = new RubygemsHosted(storage.mock, derived);
        RubygemsProxy proxy = new RubygemsProxy(proxyService, storage.mock, health, negativeCache);
        indexes = new RubygemsIndexes(hosted, proxy, groups, derived);
        RubygemsApi api = new RubygemsApi(hosted, proxy, groups);
        handler = new RubygemsFormatHandler(storage.mock, hosted, indexes, api, groups, users, tokens, derived);
    }

    // --- requests -----------------------------------------------------------------------------------

    ResponseEntity<?> get(Repository repo, String path) throws IOException {
        return handler.handle(RubygemsTestSupport.request(repo, "GET", path));
    }

    ResponseEntity<?> get(Repository repo, String path, Map<String, String> headers) throws IOException {
        return handler.handle(RubygemsTestSupport.request(repo, "GET", path, headers, null, user("admin")));
    }

    ResponseEntity<?> push(Repository repo, byte[] gem) throws IOException {
        return push(repo, gem, user("alice"));
    }

    ResponseEntity<?> push(Repository repo, byte[] gem, GranaryPrincipal principal) throws IOException {
        return handler.handle(RubygemsTestSupport.request(repo, "POST", "api/v1/gems",
                Map.of("Content-Type", "application/octet-stream"), gem, principal));
    }

    /** {@code gem yank} sends its fields as a form body in a DELETE; unyank uses PUT. */
    ResponseEntity<?> form(Repository repo, String method, String path, String body) throws IOException {
        return handler.handle(RubygemsTestSupport.request(repo, method, path,
                Map.of("Content-Type", "application/x-www-form-urlencoded"), body.getBytes(StandardCharsets.UTF_8),
                user("alice")));
    }

    ResponseEntity<?> withParameters(Repository repo, String method, String path, GranaryPrincipal principal,
                                     Map<String, String> parameters) throws IOException {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        parameters.forEach(http::addParameter);
        return handler.handle(new RepositoryRequest(repo, path, http, principal, baseUrl(repo)));
    }

    // --- responses ----------------------------------------------------------------------------------

    static byte[] bytes(ResponseEntity<?> response) {
        Object body = response.getBody();
        try {
            return switch (body) {
                case null -> new byte[0];
                case byte[] b -> b;
                case String s -> s.getBytes(StandardCharsets.UTF_8);
                case Resource r -> {
                    try (InputStream in = r.getInputStream()) {
                        yield in.readAllBytes();
                    }
                }
                case Map<?, ?> m -> m.toString().getBytes(StandardCharsets.UTF_8);
                default -> throw new AssertionError("unexpected body " + body.getClass());
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String text(ResponseEntity<?> response) {
        return new String(bytes(response), StandardCharsets.UTF_8);
    }

    static Object message(ResponseEntity<?> response) {
        return response.getBody() instanceof Map<?, ?> m ? m.get("message") : text(response);
    }

    static HttpStatus status(ResponseEntity<?> response) {
        return HttpStatus.valueOf(response.getStatusCode().value());
    }
}
