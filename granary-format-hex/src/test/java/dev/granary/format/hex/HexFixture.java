package dev.granary.format.hex;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.ApiTokenService;
import dev.granary.core.security.UserService;
import dev.granary.core.storage.StoredContent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The Hex handler wired the way Spring wires it, over an in-memory storage, a mocked proxy service (whose
 * upstream answers the tests define per path) and a mocked group resolver.
 */
final class HexFixture {

    final HexFakeStorage storage = new HexFakeStorage();
    final ProxyService proxy = mock(ProxyService.class);
    final GroupResolver groups = mock(GroupResolver.class);
    final ApiTokenService tokens = mock(ApiTokenService.class);
    final UserService users = mock(UserService.class);
    final HexSigningKey key;
    final HexHosted hosted;
    final HexContent content;
    final HexApi api;
    final HexFormatHandler handler;

    HexFixture(HexSigningKey key) {
        this.key = key;
        this.hosted = new HexHosted(storage.mock, key);
        this.content = new HexContent(storage.mock, proxy, groups, hosted, key);
        this.api = new HexApi(hosted, groups, tokens, users);
        this.handler = new HexFormatHandler(storage.mock, groups, content, api, key);
        when(proxy.fetch(any(), any(), any())).thenReturn(Optional.empty());
    }

    void members(Repository group, Repository... members) {
        when(groups.leafMembers(eq(group))).thenReturn(List.of(members));
    }

    /** What the proxy repository answers for {@code path} (cached upstream content). */
    StoredContent upstream(Repository proxyRepo, String path, byte[] bytes) {
        StoredContent stored = storage.put(proxyRepo, path, bytes, "application/octet-stream", true);
        when(proxy.fetch(eq(proxyRepo), eq(path), any())).thenReturn(Optional.of(stored));
        return stored;
    }

    StoredContent upstreamText(Repository proxyRepo, String path, String text) {
        return upstream(proxyRepo, path, text.getBytes(StandardCharsets.UTF_8));
    }

    HexHosted.Published publish(Repository repo, String name, String version) {
        return hosted.publish(repo, HexTestSupport.release(name, version), null, false, HexTestSupport.user("alice"));
    }
}
