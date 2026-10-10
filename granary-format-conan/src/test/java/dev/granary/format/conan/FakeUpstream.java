package dev.granary.format.conan;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link ProxyService} and {@link UpstreamClient} pair over a map of upstream URLs, with the caching rules of the
 * real proxy service: artifacts are served from the cache once stored, documents are fetched again (the tests treat
 * every document as expired), and an offline proxy or an unreachable upstream serves whatever is cached.
 */
final class FakeUpstream {

    /** One request that reached the "network". */
    record Call(String uri, String cachePath, Repository repo, Map<String, String> headers, boolean metadata) {
    }

    final ProxyService proxy = mock(ProxyService.class);
    final UpstreamClient client = mock(UpstreamClient.class);
    final Map<String, byte[]> responses = new HashMap<>();
    final List<Call> calls = new ArrayList<>();
    boolean offline;
    boolean down;
    /** What the health tracker reports, when a test needs something specific (such as a recent 401). */
    UpstreamHealth.Status status;
    /** The content type the "upstream" sends with files; {@code conan_server} sends {@code auto}. */
    String fileContentType = "application/octet-stream";

    private final FakeStorage storage;

    FakeUpstream(FakeStorage storage) {
        this.storage = storage;
        when(client.upstreamUri(any(), anyString())).thenAnswer(inv -> {
            Repository repo = inv.getArgument(0);
            return URI.create(UrlPaths.join(repo.getRemoteUrl(), inv.getArgument(1)));
        });
        when(proxy.isOffline(any())).thenAnswer(inv -> offline);
        // like UpstreamHealth: a failing upstream counts as unreachable until it answers again
        when(proxy.status(any())).thenAnswer(inv -> status != null ? status : down
                ? new UpstreamHealth.Status(false, 3, "connection refused", null, null, null)
                : new UpstreamHealth.Status(true, 0, null, null, null, null));
        when(proxy.fetch(any(Repository.class), anyString(), any(URI.class), any(FetchOptions.class), anyMap()))
                .thenAnswer(inv -> fetch(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                        inv.getArgument(3), inv.getArgument(4)));
    }

    void put(String uri, String body) {
        responses.put(uri, body.getBytes(StandardCharsets.UTF_8));
    }

    void put(String uri, byte[] body) {
        responses.put(uri, body);
    }

    List<String> uris() {
        return calls.stream().map(Call::uri).toList();
    }

    long count(String uri) {
        return calls.stream().filter(c -> c.uri().equals(uri)).count();
    }

    private Optional<StoredContent> fetch(Repository repo, String cachePath, URI uri, FetchOptions options,
                                          Map<String, String> headers) {
        Optional<StoredContent> cached = storage.find(repo, cachePath);
        if (cached.isPresent() && !options.metadata()) {
            return cached;
        }
        if (offline) {
            return cached;
        }
        calls.add(new Call(uri.toString(), cachePath, repo, Map.copyOf(headers), options.metadata()));
        if (down) {
            return cached;
        }
        byte[] body = responses.get(uri.toString());
        if (body == null) {
            return cached;
        }
        return Optional.of(storage.putStream(repo, cachePath, new ByteArrayInputStream(body),
                options.metadata() ? "application/json" : fileContentType, options.metadata()));
    }
}
