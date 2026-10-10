package dev.granary.format.pub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.repository.GroupResolver;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PubResolverTest {

    private static final String GROUP_URL = "http://granary.test/repository/pub-group";

    @TempDir
    Path tmp;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final PubTestSupport.FakeStorage storage = new PubTestSupport.FakeStorage();
    private final ProxyService proxyService = mock(ProxyService.class);
    private final GroupResolver groups = mock(GroupResolver.class);
    private PubHosted hosted;
    private PubResolver resolver;
    private Repository hostedRepo;
    private Repository proxyRepo;
    private Repository group;

    @BeforeEach
    void setUp() {
        hosted = new PubHosted(storage.mock, json);
        PubProxy proxy = new PubProxy(proxyService, storage.mock, new UpstreamClient(new GranaryProperties()), json);
        resolver = new PubResolver(hosted, proxy, groups);
        hostedRepo = PubTestSupport.repository("pub-hosted", RepositoryType.HOSTED);
        proxyRepo = PubTestSupport.repository("pub-dev", RepositoryType.PROXY);
        group = PubTestSupport.repository("pub-group", RepositoryType.GROUP);
        group.setMembers(List.of("pub-hosted", "pub-dev"));
        when(groups.leafMembers(group)).thenReturn(List.of(hostedRepo, proxyRepo));
        // the proxy "fetches" whatever is in its cache
        when(proxyService.fetch(any(), anyString(), any(FetchOptions.class), anyMap()))
                .thenAnswer(inv -> storage.find(inv.getArgument(0), inv.getArgument(1)));
        when(proxyService.fetch(any(), anyString(), any(URI.class), any(), anyMap()))
                .thenAnswer(inv -> storage.find(inv.getArgument(0), inv.getArgument(1)));
        when(proxyService.status(any())).thenReturn(new UpstreamHealth.Status(true, 0, null, Instant.now(), null, null));
    }

    private void publish(String name, String version) throws Exception {
        Path file = Files.createTempFile(tmp, "a", ".tar.gz");
        Files.write(file, PubTestSupport.archive(name, version));
        hosted.publish(hostedRepo, file);
    }

    private void upstream(String name, String advisoriesUpdated, byte[] archive, String... versions) {
        StringBuilder sb = new StringBuilder("{\"name\":\"" + name + "\",");
        if (advisoriesUpdated != null) {
            sb.append("\"advisoriesUpdated\":\"").append(advisoriesUpdated).append("\",");
        }
        sb.append("\"versions\":[");
        for (int i = 0; i < versions.length; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"version\":\"").append(versions[i]).append("\",\"pubspec\":{},")
                    .append("\"archive_url\":\"https://pub.dev/api/archives/").append(name).append('-').append(versions[i])
                    .append(".tar.gz\",\"archive_sha256\":\"").append(PubTestSupport.sha256(archive)).append("\"}");
        }
        storage.put(proxyRepo, "api/packages/" + name, sb.append("]}").toString().getBytes(StandardCharsets.UTF_8),
                "application/json", true);
        for (String version : versions) {
            storage.put(proxyRepo, "packages/" + name + "/versions/" + version + ".tar.gz", archive, "application/gzip", false);
        }
    }

    private static List<String> versions(JsonNode listing) {
        return PubListing.entries(listing).stream().map(e -> e.version().text()).toList();
    }

    @Test
    void hostedListingsGetArchiveUrlsOfTheRepositoryAsked() throws Exception {
        publish("demo", "1.0.0");
        ObjectNode listing = resolver.listing(hostedRepo, "demo", GROUP_URL).orElseThrow();
        assertThat(listing.get("latest").get("archive_url").stringValue()).isEqualTo(GROUP_URL + "/packages/demo/versions/1.0.0.tar.gz");
        assertThat(resolver.listing(hostedRepo, "nope", GROUP_URL)).isEmpty();
    }

    @Test
    void groupsMergeTheirMembersWithHostedFirst() throws Exception {
        publish("shared", "1.0.0");
        byte[] upstreamArchive = PubTestSupport.archive("shared", "9.9.9");
        upstream("shared", null, upstreamArchive, "1.0.0", "2.0.0");
        ObjectNode merged = resolver.listing(group, "shared", GROUP_URL).orElseThrow();
        assertThat(versions(merged)).containsExactly("1.0.0", "2.0.0");
        String hostedSha = PubTestSupport.sha256(storage.bytes(hostedRepo, "packages/shared/versions/1.0.0.tar.gz"));
        assertThat(PubListing.find(merged, "1.0.0").orElseThrow().sha256()).contains(hostedSha);
        assertThat(merged.get("latest").get("version").stringValue()).isEqualTo("2.0.0");
        for (JsonNode v : merged.get("versions")) {
            assertThat(v.get("archive_url").stringValue()).startsWith(GROUP_URL + "/packages/shared/versions/");
        }
    }

    @Test
    void groupArchivesComeFromTheMemberThatOwnsTheVersion() throws Exception {
        publish("shared", "1.0.0");
        byte[] upstreamArchive = PubTestSupport.archive("shared", "1.0.0-upstream");
        upstream("shared", null, upstreamArchive, "1.0.0", "2.0.0");
        byte[] hostedBytes = storage.bytes(hostedRepo, "packages/shared/versions/1.0.0.tar.gz");
        assertThat(resolver.archive(group, "shared", "1.0.0").orElseThrow().readAllBytes()).isEqualTo(hostedBytes);
        assertThat(resolver.archive(group, "shared", "2.0.0").orElseThrow().readAllBytes()).isEqualTo(upstreamArchive);
        assertThat(resolver.archive(group, "shared", "3.0.0")).isEmpty();
    }

    @Test
    void aMemberThatDoesNotListTheVersionIsAskedLast() throws Exception {
        // the proxy has the file cached but its listing does not mention it
        storage.put(proxyRepo, "packages/orphan/versions/1.0.0.tar.gz", new byte[] {1}, "application/gzip", false);
        assertThat(resolver.archive(group, "orphan", "1.0.0")).isPresent();
    }

    @Test
    void groupsWithoutThePackageAnswerEmpty() {
        assertThat(resolver.listing(group, "nope", GROUP_URL)).isEmpty();
        assertThat(resolver.advisories(group, "nope")).isEmpty();
    }

    @Test
    void advisoriesAreEmptyForHostedPackagesAndMergedInGroups() throws Exception {
        publish("demo", "1.0.0");
        assertThat(resolver.advisories(hostedRepo, "demo")).contains(PubListing.noAdvisories());
        assertThat(resolver.advisories(hostedRepo, "nope")).isEmpty();

        upstream("demo", "2026-01-01T00:00:00Z", new byte[] {1}, "0.1.0");
        storage.put(proxyRepo, "api/packages/demo/advisories",
                "{\"advisories\":[{\"id\":\"GHSA-1\"}],\"advisoriesUpdated\":\"2026-01-01T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8),
                "application/json", true);
        ObjectNode merged = resolver.advisories(group, "demo").orElseThrow();
        assertThat(merged.get("advisories")).hasSize(1);
        assertThat(merged.get("advisoriesUpdated").stringValue()).isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    void proxyListingsDropTheAdvisoriesDateWhenAdvisoriesCannotBeServed() {
        upstream("http", "2026-05-04T16:03:47.124153Z", new byte[] {1}, "1.0.0");
        assertThat(resolver.listing(proxyRepo, "http", GROUP_URL).orElseThrow().has("advisoriesUpdated")).isTrue();
        when(proxyService.isOffline(proxyRepo)).thenReturn(true);
        assertThat(resolver.listing(proxyRepo, "http", GROUP_URL).orElseThrow().has("advisoriesUpdated")).isFalse();
        storage.put(proxyRepo, "api/packages/http/advisories", "{}".getBytes(StandardCharsets.UTF_8), "application/json", true);
        assertThat(resolver.listing(proxyRepo, "http", GROUP_URL).orElseThrow().has("advisoriesUpdated")).isTrue();
    }

    @Test
    void groupArchiveLookupDoesNotAskTheUpstreamTwice() {
        upstream("only_upstream", null, new byte[] {1}, "1.0.0");
        storage.assets.remove(proxyRepo.getId() + "|packages/only_upstream/versions/1.0.0.tar.gz");
        assertThat(resolver.archive(group, "only_upstream", "1.0.0")).isEmpty();
        verify(proxyService, times(1))
                .fetch(eq(proxyRepo), eq("packages/only_upstream/versions/1.0.0.tar.gz"), any(URI.class), any(), anyMap());
        verify(proxyService, never()).fetch(eq(hostedRepo), anyString(), any(URI.class), any(), anyMap());
    }
}
