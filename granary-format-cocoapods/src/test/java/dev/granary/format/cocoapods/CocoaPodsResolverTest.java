package dev.granary.format.cocoapods;

import static dev.granary.format.cocoapods.CocoaPodsTestSupport.httpPodspec;
import static dev.granary.format.cocoapods.CocoaPodsTestSupport.podspec;
import static dev.granary.format.cocoapods.CocoaPodsTestSupport.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StoredContent;
import dev.granary.format.cocoapods.CocoaPodsTestSupport.FakeStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CocoaPodsResolverTest {

    private static final String GROUP_URL = "http://granary.test/repository/cocoapods-group";
    private static final String HOSTED_URL = "http://granary.test/repository/cocoapods-hosted";
    private static final String AF = "Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json";

    private final ProxyService proxyService = mock(ProxyService.class);
    private final GroupResolver groups = mock(GroupResolver.class);
    private FakeStorage storage;
    private CocoaPodsHosted hosted;
    private CocoaPodsResolver resolver;
    private Repository hostedRepo;
    private Repository proxyRepo;
    private Repository group;

    @BeforeEach
    void setUp() {
        storage = new FakeStorage();
        hosted = new CocoaPodsHosted(storage.mock);
        resolver = new CocoaPodsResolver(hosted, new CocoaPodsProxy(proxyService, storage.mock, mock(UpstreamClient.class), new NegativeCache()), groups);
        hostedRepo = CocoaPodsTestSupport.repository("cocoapods-hosted", RepositoryType.HOSTED);
        proxyRepo = CocoaPodsTestSupport.repository("cocoapods-cdn", RepositoryType.PROXY);
        group = CocoaPodsTestSupport.repository("cocoapods-group", RepositoryType.GROUP);
        when(groups.leafMembers(group)).thenReturn(List.of(hostedRepo, proxyRepo));
        when(proxyService.fetch(any(), any(String.class), any(FetchOptions.class))).thenReturn(Optional.empty());
    }

    private void upstream(String path, String body, FetchOptions options) {
        StoredContent content = storage.put(proxyRepo, path, body);
        when(proxyService.fetch(proxyRepo, path, options)).thenReturn(Optional.of(content));
    }

    private void push(String json) throws IOException {
        hosted.push(hostedRepo, utf8(json), null, null, null, HOSTED_URL);
    }

    @Test
    void groupShardsAreTheUnionOfTheirMembers() throws IOException {
        push(podspec("AFNetworking", "4.0.2-internal"));
        upstream("all_pods_versions_a_7_5.txt", "AFNetworking/4.0.0/4.0.1\nAppseeAnalytics/2.4.7\n", FetchOptions.index());
        Map<String, List<String>> shard = resolver.shard(group, "a75").orElseThrow();
        assertThat(new String(PodIndex.renderShard(shard), StandardCharsets.UTF_8))
                .isEqualTo("AFNetworking/4.0.0/4.0.1/4.0.2-internal\nAppseeAnalytics/2.4.7\n");
    }

    @Test
    void aGroupAnswersWithWhatItHasWhenAMemberCannotAnswer() throws IOException {
        push(podspec("AFNetworking", "4.0.2-internal"));
        // the proxy has nothing (upstream down, nothing cached)
        assertThat(resolver.shard(group, "a75").orElseThrow()).containsOnlyKeys("AFNetworking");
        // nothing at all while a member is missing: a retryable miss instead of an empty shard
        assertThat(resolver.shard(group, "fff")).isEmpty();
        // but a hosted-only group answers an empty shard
        Repository hostedOnly = CocoaPodsTestSupport.repository("hosted-only", RepositoryType.GROUP);
        when(groups.leafMembers(hostedOnly)).thenReturn(List.of(hostedRepo));
        assertThat(resolver.shard(hostedOnly, "fff")).contains(Map.of());
        Repository empty = CocoaPodsTestSupport.repository("empty", RepositoryType.GROUP);
        when(groups.leafMembers(empty)).thenReturn(List.of());
        assertThat(resolver.shard(empty, "a75")).isEmpty();
        assertThat(resolver.podNames(empty)).isEmpty();
    }

    @Test
    void aMemberWithACoarserLayoutIsFilteredAndAFinerOneIsLeftOut() {
        upstream(VersionFile.PATH, "last: 1.16.2\nprefix_lengths: [1]\n", FetchOptions.index());
        upstream("all_pods_versions_a.txt", "AFNetworking/4.0.1\nOtherA/1.0\n", FetchOptions.index());
        assertThat(PodNames.md5Hex("OtherA")).doesNotStartWith("a75");
        Map<String, List<String>> shard = resolver.shard(proxyRepo, "a75").orElseThrow();
        assertThat(shard).containsOnlyKeys("AFNetworking");

        upstream(VersionFile.PATH, "last: 1.16.2\nprefix_lengths: [1, 1, 1, 1]\n", FetchOptions.index());
        assertThat(resolver.shard(proxyRepo, "a75")).isEmpty();

        upstream(VersionFile.PATH, "last: 1.16.2\n", FetchOptions.index());
        upstream("all_pods_versions.txt", "AFNetworking/4.0.1\nAlamofire/5.0\n", FetchOptions.index());
        assertThat(resolver.shard(proxyRepo, "a75").orElseThrow()).containsOnlyKeys("AFNetworking");
        assertThat(resolver.shard(proxyRepo, "da2").orElseThrow()).containsOnlyKeys("Alamofire");
    }

    @Test
    void podNamesAndDeprecatedListsAreMergedInTheStandardLayout() throws IOException {
        push(podspec("Mine", "1.0"));
        push(podspec("Old", "1.0").replace("\"license\"", "\"deprecated\": true, \"license\""));
        upstream(CocoaPodsPath.ALL_PODS, "AFNetworking\nMine\n", FetchOptions.index());
        upstream(VersionFile.PATH, "last: 1.16.2\n", FetchOptions.index());
        upstream(CocoaPodsPath.DEPRECATED, "Specs/AFCategory/0.1.0/AFCategory.podspec.json\ngarbage\n", FetchOptions.index());
        assertThat(resolver.podNames(group).orElseThrow()).containsExactlyInAnyOrder("Mine", "Old", "AFNetworking");
        assertThat(resolver.deprecated(group).orElseThrow()).containsExactlyInAnyOrder(
                ShardLayout.STANDARD.podspecPath("Old", "1.0"), "Specs/0/0/4/AFCategory/0.1.0/AFCategory.podspec.json");
        assertThat(resolver.deprecated(hostedRepo).orElseThrow()).containsExactly(ShardLayout.STANDARD.podspecPath("Old", "1.0"));
        assertThat(resolver.podNames(proxyRepo).orElseThrow()).containsExactly("AFNetworking", "Mine");
    }

    @Test
    void theDeprecatedListOfAStandardUpstreamIsPassedThrough() {
        upstream(CocoaPodsPath.DEPRECATED, "Specs/0/0/4/AFCategory/0.1.0/AFCategory.podspec.json\n", FetchOptions.index());
        assertThat(resolver.deprecated(proxyRepo).orElseThrow()).containsExactly("Specs/0/0/4/AFCategory/0.1.0/AFCategory.podspec.json");
    }

    @Test
    void podspecsComeFromTheFirstMemberThatHasThem() throws IOException {
        upstream(AF, podspec("AFNetworking", "4.0.1"), FetchOptions.artifact());
        CocoaPodsResolver.Found fromProxy = resolver.podspec(group, "AFNetworking", "4.0.1").orElseThrow();
        assertThat(fromProxy.holder()).isSameAs(proxyRepo);

        push(podspec("AFNetworking", "4.0.1").replace("The AFNetworking pod.", "patched in house"));
        CocoaPodsResolver.Found fromHosted = resolver.podspec(group, "AFNetworking", "4.0.1").orElseThrow();
        assertThat(fromHosted.holder()).isSameAs(hostedRepo);
        assertThat(fromHosted.content().readUtf8()).contains("patched in house");
        assertThat(resolver.podspec(group, "Nope", "1.0")).isEmpty();
    }

    @Test
    void proxiedHttpSourcesPointAtTheRepositoryTheClientUsed() throws IOException {
        String url = "https://github.com/acme/Zipped/releases/download/1.2.0/Zipped-1.2.0.zip";
        String path = ShardLayout.STANDARD.podspecPath("Zipped", "1.2.0");
        upstream(path, httpPodspec("Zipped", "1.2.0", url).replace("\"flatten\": true",
                "\"flatten\": true, \"type\": \"zip\", \"sha256\": \"abc\", \"headers\": [\"Accept: application/octet-stream\"]"), FetchOptions.artifact());
        CocoaPodsResolver.Found found = resolver.podspec(group, "Zipped", "1.2.0").orElseThrow();

        JsonNode source = Podspec.parse(resolver.render(group, found, "Zipped", "1.2.0", GROUP_URL)).node().get("source");
        assertThat(source.get("http").asString()).isEqualTo(GROUP_URL + "/sources/Zipped/1.2.0/Zipped-1.2.0.zip");
        assertThat(source.get("sha256").asString()).isEqualTo("abc");
        assertThat(source.get("type").asString()).isEqualTo("zip");
        assertThat(source.get("flatten").asBoolean()).isTrue();
        assertThat(source.has("headers")).isFalse();

        String direct = "http://granary.test/repository/cocoapods-cdn";
        assertThat(Podspec.parse(resolver.render(proxyRepo, found, "Zipped", "1.2.0", direct)).httpSource())
                .contains(direct + "/sources/Zipped/1.2.0/Zipped-1.2.0.zip");
    }

    @Test
    void rewritingCanBeSwitchedOffOnTheProxyOrTheGroup() throws IOException {
        String url = "https://example.com/Zipped-1.2.0.zip";
        upstream(ShardLayout.STANDARD.podspecPath("Zipped", "1.2.0"), httpPodspec("Zipped", "1.2.0", url), FetchOptions.artifact());
        CocoaPodsResolver.Found found = resolver.podspec(proxyRepo, "Zipped", "1.2.0").orElseThrow();
        byte[] original = found.content().readAllBytes();

        group.setFormatSettings(Map.of(CocoaPodsProxy.REWRITE_SETTING, "false"));
        assertThat(resolver.render(group, found, "Zipped", "1.2.0", GROUP_URL)).isEqualTo(original);
        group.setFormatSettings(Map.of());
        proxyRepo.setFormatSettings(Map.of(CocoaPodsProxy.REWRITE_SETTING, "false"));
        assertThat(resolver.render(group, found, "Zipped", "1.2.0", GROUP_URL)).isEqualTo(original);
        assertThat(resolver.render(proxyRepo, found, "Zipped", "1.2.0", GROUP_URL)).isEqualTo(original);
    }

    @Test
    void gitSourcesAndUnparseablePodspecsAreServedByteForByte() throws IOException {
        String gitSpec = podspec("AFNetworking", "4.0.1");
        upstream(AF, gitSpec, FetchOptions.artifact());
        CocoaPodsResolver.Found git = resolver.podspec(group, "AFNetworking", "4.0.1").orElseThrow();
        assertThat(new String(resolver.render(group, git, "AFNetworking", "4.0.1", GROUP_URL), StandardCharsets.UTF_8)).isEqualTo(gitSpec);

        upstream(ShardLayout.STANDARD.podspecPath("Broken", "1.0"), "{not json", FetchOptions.artifact());
        CocoaPodsResolver.Found broken = resolver.podspec(group, "Broken", "1.0").orElseThrow();
        assertThat(new String(resolver.render(group, broken, "Broken", "1.0", GROUP_URL), StandardCharsets.UTF_8)).isEqualTo("{not json");

        String ftp = httpPodspec("Ftp", "1.0", "ftp://example.com/Ftp.zip");
        upstream(ShardLayout.STANDARD.podspecPath("Ftp", "1.0"), ftp, FetchOptions.artifact());
        CocoaPodsResolver.Found ftpFound = resolver.podspec(group, "Ftp", "1.0").orElseThrow();
        assertThat(new String(resolver.render(group, ftpFound, "Ftp", "1.0", GROUP_URL), StandardCharsets.UTF_8)).isEqualTo(ftp);
    }

    @Test
    void hostedArchivesFollowTheUrlTheClientUsedButExternalUrlsStay() throws IOException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("pod", ".zip");
        try {
            java.nio.file.Files.write(file, CocoaPodsTestSupport.zip("a", "b"));
            hosted.push(hostedRepo, utf8(podspec("Bin", "1.0")), null, null, new CocoaPodsHosted.Archive(file, "Bin.zip"), HOSTED_URL);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
        CocoaPodsResolver.Found found = resolver.podspec(group, "Bin", "1.0").orElseThrow();
        assertThat(Podspec.parse(resolver.render(group, found, "Bin", "1.0", GROUP_URL)).httpSource())
                .contains(GROUP_URL + "/sources/Bin/1.0/Bin.zip");
        // served directly at the URL it was pushed to, the podspec is returned unchanged
        assertThat(resolver.render(hostedRepo, found, "Bin", "1.0", HOSTED_URL)).isEqualTo(found.content().readAllBytes());

        push(httpPodspec("Ext", "1.0", "https://example.com/Ext.zip"));
        CocoaPodsResolver.Found ext = resolver.podspec(group, "Ext", "1.0").orElseThrow();
        assertThat(Podspec.parse(resolver.render(group, ext, "Ext", "1.0", GROUP_URL)).httpSource()).contains("https://example.com/Ext.zip");
    }

    @Test
    void sourcesComeFromTheFirstMemberThatHasThem() throws IOException {
        assertThat(resolver.source(group, "X", "1.0", "x.zip")).isEmpty();
        storage.put(proxyRepo, "sources/X/1.0/x.zip", CocoaPodsTestSupport.zip("p", "p"), "application/zip", false);
        assertThat(resolver.source(group, "X", "1.0", "x.zip")).isPresent();
        storage.put(hostedRepo, "sources/X/1.0/x.zip", CocoaPodsTestSupport.zip("h", "h"), "application/zip", false);
        assertThat(resolver.source(group, "X", "1.0", "x.zip").orElseThrow().asset().getRepositoryId()).isEqualTo(hostedRepo.getId());
    }

    @Test
    void layoutsPresentedToClients() {
        upstream(VersionFile.PATH, "last: 1.16.2\nprefix_lengths: [2]\n", FetchOptions.index());
        assertThat(resolver.layout(proxyRepo).prefixLengths()).containsExactly(2);
        assertThat(resolver.layout(group)).isEqualTo(ShardLayout.STANDARD);
        assertThat(resolver.layout(hostedRepo)).isEqualTo(ShardLayout.STANDARD);
    }
}
