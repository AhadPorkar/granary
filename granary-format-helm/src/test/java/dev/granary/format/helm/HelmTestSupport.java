package dev.granary.format.helm;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Fixtures shared by the Helm unit tests: repositories, requests as the clients send them, and a wired handler. */
final class HelmTestSupport {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final String SERVER = "http://granary.test";

    private HelmTestSupport() {
    }

    static Repository hosted(String name) {
        return repository(name, RepositoryType.HOSTED);
    }

    static Repository proxy(String name, String remoteUrl) {
        Repository r = repository(name, RepositoryType.PROXY);
        r.setRemoteUrl(remoteUrl);
        return r;
    }

    static Repository group(String name, String... members) {
        Repository r = repository(name, RepositoryType.GROUP);
        r.setMembers(List.of(members));
        return r;
    }

    static Repository repository(String name, RepositoryType type) {
        Repository r = new Repository();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setFormat(HelmFormatHandler.FORMAT);
        r.setType(type);
        r.setBlobStore("default");
        return r;
    }

    static String baseUrl(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    static GranaryPrincipal user() {
        return new GranaryPrincipal(UUID.randomUUID(), "dev", Set.of("developer"),
                Set.of("repository:read:*", "repository:write:*"), false, GranaryPrincipal.AuthMethod.API_TOKEN);
    }

    static RepositoryRequest get(Repository repo, String path) {
        return request(repo, "GET", path, null, null);
    }

    static RepositoryRequest request(Repository repo, String method, String path, byte[] body, String contentType) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        if (body != null) {
            http.setContent(body);
        }
        if (contentType != null) {
            http.setContentType(contentType);
        }
        return new RepositoryRequest(repo, path, http, user(), baseUrl(repo));
    }

    /** A multipart POST as {@code helm cm-push} and {@code curl -F chart=@… -F prov=@…} send it. */
    static RepositoryRequest multipart(Repository repo, String path, Map<String, byte[]> files) {
        MockMultipartHttpServletRequest http = new MockMultipartHttpServletRequest();
        http.setMethod("POST");
        http.setRequestURI("/repository/" + repo.getName() + "/" + path);
        http.setContentType("multipart/form-data; boundary=x");
        files.forEach((field, bytes) -> http.addFile(new MockMultipartFile(field, field + ".bin", "application/octet-stream", bytes)));
        return new RepositoryRequest(repo, path, http, user(), baseUrl(repo));
    }

    static RepositoryRequest withHeader(RepositoryRequest request, String name, String value) {
        ((MockHttpServletRequest) request.http()).addHeader(name, value);
        return request;
    }

    static RepositoryRequest withQuery(RepositoryRequest request, String name, String value) {
        ((MockHttpServletRequest) request.http()).setParameter(name, value);
        return request;
    }

    static byte[] body(ResponseEntity<?> response) {
        Object body = response.getBody();
        if (body == null) {
            return new byte[0];
        }
        if (body instanceof byte[] bytes) {
            return bytes;
        }
        if (body instanceof Resource resource) {
            try (InputStream in = resource.getInputStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return MAPPER.writeValueAsBytes(body);
    }

    static String text(ResponseEntity<?> response) {
        return new String(body(response), StandardCharsets.UTF_8);
    }

    /** A provenance file in the shape {@code helm package --sign} writes (the signature itself is not checked). */
    static String provenance(String name, String version, String sha256) {
        return """
                -----BEGIN PGP SIGNED MESSAGE-----
                Hash: SHA512

                apiVersion: v2
                appVersion: 1.16.0
                description: A Helm chart for Kubernetes
                name: %1$s
                type: application
                version: %2$s

                ...
                files:
                  %1$s-%2$s.tgz: sha256:%3$s
                -----BEGIN PGP SIGNATURE-----

                wsBcBAEBCgAQBQJqyeDWCRCJuSJKCyqK+AAAY4cIAG7GAKgZAGCAE5ZO9fwoLkN4
                =x+rj
                -----END PGP SIGNATURE-----""".formatted(name, version, sha256);
    }

    static String sha256(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(HelmHosted.sha256().digest(bytes));
    }

    /** A {@link ProxyService} double: upstream documents by URL, cached into the fake storage like the real one. */
    static final class FakeUpstream {
        final Map<String, byte[]> documents = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        final ProxyService mock = mock(ProxyService.class);
        boolean offline;

        FakeUpstream(FakeStorage storage) {
            when(mock.fetch(any(), anyString(), any(URI.class), any(), anyMap())).thenAnswer(inv -> {
                Repository repo = inv.getArgument(0);
                String path = inv.getArgument(1);
                URI uri = inv.getArgument(2);
                FetchOptions options = inv.getArgument(3);
                Optional<StoredContent> cached = storage.find(repo, path);
                if (cached.isPresent() && !options.metadata()) {
                    return cached;
                }
                if (offline) {
                    return cached;
                }
                requested.add(uri.toString());
                byte[] body = documents.get(uri.toString());
                if (body == null) {
                    return cached;
                }
                return Optional.of(storage.put(repo, path, body, "application/octet-stream", options.metadata()));
            });
        }

        void serve(String url, byte[] body) {
            documents.put(url, body);
        }

        void serve(String url, String body) {
            serve(url, body.getBytes(StandardCharsets.UTF_8));
        }

        long count(String url) {
            return requested.stream().filter(url::equals).count();
        }
    }

    /** The handler wired to fakes: storage in memory, a scripted upstream and explicit group members. */
    static final class Wiring {
        final FakeStorage storage = new FakeStorage();
        final FakeUpstream upstream = new FakeUpstream(storage);
        final GroupResolver groups = mock(GroupResolver.class);
        final HelmHosted hosted = new HelmHosted(storage.mock, MAPPER);
        final HelmProxy proxy = new HelmProxy(upstream.mock, storage.mock);
        final HelmIndexes indexes = new HelmIndexes(hosted, proxy, groups);
        final HelmFormatHandler handler;

        Wiring(Path dataDir) {
            GranaryProperties properties = new GranaryProperties();
            properties.setDataDir(dataDir);
            handler = new HelmFormatHandler(storage.mock, hosted, indexes, groups, properties);
        }

        void members(Repository group, Repository... members) {
            when(groups.leafMembers(group)).thenReturn(List.of(members));
        }

        ResponseEntity<?> handle(RepositoryRequest request) {
            try {
                return handler.handle(request);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        ResponseEntity<?> upload(Repository repo, byte[] chart) {
            return handle(request(repo, "POST", "api/charts", chart, "application/octet-stream"));
        }
    }
}
