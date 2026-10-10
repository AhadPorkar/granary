package dev.granary.format.cargo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.User;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.AuthenticationService;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.security.UserService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The whole format wired together over a {@link FakeStorage}, a scripted upstream (URL to bytes) and mocked
 * security services, so handler-level behaviour can be tested without Spring.
 */
final class CargoFixture {

    final FakeStorage storage = new FakeStorage();
    final ProxyService proxy = mock(ProxyService.class);
    final UpstreamClient upstreamClient = mock(UpstreamClient.class);
    final GroupResolver groups = mock(GroupResolver.class);
    final UserService users = mock(UserService.class);
    final AuthenticationService authentication = mock(AuthenticationService.class);
    final CargoHosted hosted = new CargoHosted(storage.mock, users);
    final CargoIndexes indexes = new CargoIndexes(storage.mock, proxy, groups, hosted);
    final CargoSearch search = new CargoSearch(storage.mock, proxy, upstreamClient, indexes, hosted);
    final CargoFormatHandler handler;

    /** What the fake upstream serves, by absolute URL. */
    final Map<URI, byte[]> upstream = new LinkedHashMap<>();
    /** Every upstream URL the proxy was asked to fetch, in order. */
    final List<URI> fetched = new ArrayList<>();
    final Set<String> offline = new HashSet<>();
    final Map<String, List<Repository>> members = new LinkedHashMap<>();

    CargoFixture(Path dataDir) {
        GranaryProperties properties = new GranaryProperties();
        properties.setDataDir(dataDir);
        handler = new CargoFormatHandler(storage.mock, indexes, hosted, search, groups, authentication, properties);
        when(authentication.anonymous()).thenReturn(GranaryPrincipal.anonymous(Set.of("repository:read:*")));
        when(users.find(anyString())).thenAnswer(inv -> {
            String login = inv.getArgument(0);
            if (login.startsWith("ghost")) {
                return Optional.empty();
            }
            User user = new User();
            user.setUsername(login);
            return Optional.of(user);
        });
        when(proxy.isOffline(any())).thenAnswer(inv -> offline.contains(((Repository) inv.getArgument(0)).getName()));
        when(proxy.status(any())).thenReturn(new UpstreamHealth.Status(true, 0, null, null, null, null));
        when(proxy.fetch(any(), anyString(), any(URI.class), any(), any())).thenAnswer(inv -> fetch(inv.getArgument(0),
                inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        when(groups.leafMembers(any())).thenAnswer(inv -> members.getOrDefault(((Repository) inv.getArgument(0)).getName(), List.of()));
    }

    /** Behaves like the real proxy cache: cached artifacts never go upstream, offline serves the cache only. */
    private Optional<StoredContent> fetch(Repository repo, String path, URI uri, FetchOptions options) {
        Optional<StoredContent> cached = storage.find(repo, path);
        if (cached.isPresent() && !options.metadata()) {
            return cached;
        }
        if (offline.contains(repo.getName())) {
            return cached;
        }
        fetched.add(uri);
        byte[] bytes = upstream.get(uri);
        if (bytes == null) {
            return cached;
        }
        String type = options.metadata() ? "text/plain" : "application/gzip";
        StoredContent stored = storage.putStream(repo, path, new ByteArrayInputStream(bytes), type, options.metadata());
        stored.asset().setOrigin(Asset.Origin.CACHED);
        stored.asset().setLastVerifiedAt(Instant.now());
        return Optional.of(stored);
    }

    void group(Repository group, Repository... leaves) {
        members.put(group.getName(), List.of(leaves));
    }

    void serve(String url, byte[] bytes) {
        upstream.put(URI.create(url), bytes);
    }

    void serve(String url, String text) {
        serve(url, text.getBytes(StandardCharsets.UTF_8));
    }

    int fetchesOf(String url) {
        URI uri = URI.create(url);
        return (int) fetched.stream().filter(uri::equals).count();
    }
}
