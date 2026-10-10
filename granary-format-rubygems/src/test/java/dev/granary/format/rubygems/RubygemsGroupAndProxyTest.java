package dev.granary.format.rubygems;

import static dev.granary.format.rubygems.RubygemsTestSupport.baseUrl;
import static dev.granary.format.rubygems.RubygemsTestSupport.gem;
import static dev.granary.format.rubygems.RubygemsTestSupport.platformGem;
import static dev.granary.format.rubygems.RubygemsTestSupport.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A group of a hosted repository and a proxy whose upstream is simulated: the mocked {@code ProxyService} caches
 * whatever the test put "upstream" and serves the cache afterwards, as the real one does.
 */
class RubygemsGroupAndProxyTest extends RubygemsHandlerTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    private final Repository hostedRepo = RubygemsTestSupport.hosted("gems-hosted");
    private final Repository proxyRepo = RubygemsTestSupport.proxy("gems-proxy");
    private final Repository groupRepo = RubygemsTestSupport.group("gems", "gems-hosted", "gems-proxy");

    /** What upstream serves; a path missing here is "not found" upstream (the cache may still have it). */
    private final Map<String, byte[]> upstream = new HashMap<>();
    private final List<String> fetches = new ArrayList<>();
    private final Map<String, FetchOptions> fetchOptions = new HashMap<>();

    private String upstreamRakeInfo;

    @BeforeEach
    void simulateUpstream() {
        when(groups.leafMembers(groupRepo)).thenReturn(List.of(hostedRepo, proxyRepo));
        when(proxyService.fetch(eq(proxyRepo), anyString(), any(), anyMap())).thenAnswer(inv -> {
            String path = inv.getArgument(1);
            fetches.add(path);
            fetchOptions.put(path, inv.getArgument(2));
            byte[] body = upstream.get(path);
            if (body == null) {
                return storage.find(proxyRepo, path);
            }
            return Optional.of(storage.put(proxyRepo, path, body));
        });

        upstreamRakeInfo = "---\n13.4.1 |checksum:" + SHA_A + ",ruby:>= 2.3,created_at:2026-04-14T08:25:34Z\n"
                + "13.4.2 |checksum:" + SHA_B + ",ruby:>= 2.3,created_at:2026-04-16T04:47:46Z\n";
        StringBuilder versions = new StringBuilder("created_at: 2026-10-01T00:00:00Z\n---\n");
        for (int i = 0; i < 50; i++) {
            versions.append("filler").append(i).append(" 1.0 ").append(CompactIndex.md5("x" + i)).append('\n');
        }
        versions.append("rake 13.4.1,13.4.2 ").append(CompactIndex.md5(upstreamRakeInfo)).append('\n');
        upstream("versions", versions.toString());
        upstream("info/rake", upstreamRakeInfo);
        upstream("names", "---\nfiller0\nrake\n");
        upstream("specs.4.8.gz", resource("ruby-specs.4.8.gz"));
        upstream("latest_specs.4.8.gz", SpecsIndex.gzip(List.of(new SpecTuple("alpha", "2.0", "ruby"),
                new SpecTuple("rake", "13.4.2", "ruby"))));
        upstream("prerelease_specs.4.8.gz", SpecsIndex.gzip(List.of(new SpecTuple("alpha", "3.0.rc1", "ruby"))));
        upstream("gems/rake-13.4.2.gem", "rake gem bytes".getBytes(StandardCharsets.UTF_8));
        upstream("quick/Marshal.4.8/rake-13.4.2.gemspec.rz", resource("rake-13.2.1.gemspec.rz"));
        upstream("api/v1/gems/rake.json", "{\"name\":\"rake\",\"version\":\"13.4.2\",\"platform\":\"ruby\","
                + "\"gem_uri\":\"https://rubygems.org/gems/rake-13.4.2.gem\",\"downloads\":5}");
        upstream("api/v1/versions/rake.json", "[{\"number\":\"13.4.2\",\"platform\":\"ruby\"},"
                + "{\"number\":\"13.4.1\",\"platform\":\"ruby\"}]");
        upstream("api/v1/versions/rake/latest.json", "{\"version\":\"13.4.2\"}");
    }

    private void upstream(String path, String body) {
        upstream(path, body.getBytes(StandardCharsets.UTF_8));
    }

    private void upstream(String path, byte[] body) {
        upstream.put(path, body);
    }

    // --- proxy ----------------------------------------------------------------------------------------

    @Test
    void proxyServesUpstreamDocumentsUnchanged() throws IOException {
        ResponseEntity<?> versions = get(proxyRepo, "versions");
        assertThat(bytes(versions)).isEqualTo(upstream.get("versions"));
        assertThat(versions.getHeaders().getETag()).isEqualTo("\"" + CompactIndex.md5(upstream.get("versions")) + "\"");
        assertThat(text(get(proxyRepo, "info/rake"))).isEqualTo(upstreamRakeInfo);
        assertThat(bytes(get(proxyRepo, "specs.4.8.gz"))).isEqualTo(resource("ruby-specs.4.8.gz"));
        assertThat(fetchOptions.get("versions").metadata()).isTrue();
        assertThat(fetchOptions.get("info/rake").metadata()).isTrue();
        assertThat(fetchOptions.get("specs.4.8.gz").metadata()).isTrue();
    }

    @Test
    void proxyCachesGemsAsArtifactsAndLinksThemToComponents() throws IOException {
        ResponseEntity<?> gem = get(proxyRepo, "gems/rake-13.4.2.gem");
        assertThat(text(gem)).isEqualTo("rake gem bytes");
        assertThat(fetchOptions.get("gems/rake-13.4.2.gem").metadata()).isFalse();
        assertThat(storage.components.values()).singleElement().satisfies(c -> {
            assertThat(c.getName()).isEqualTo("rake");
            assertThat(c.getVersion()).isEqualTo("13.4.2");
        });
        assertThat(storage.asset(proxyRepo, "gems/rake-13.4.2.gem").getComponentId()).isNotNull();

        get(proxyRepo, "quick/Marshal.4.8/rake-13.4.2.gemspec.rz");
        assertThat(storage.components).as("the quick spec joins the same component").hasSize(1);
        assertThat(storage.downloads).hasSize(1);
    }

    @Test
    void aMissingGemThatUpstreamDeniesIsAMissNotAnOutage() throws IOException {
        // rubygems.org answers 403 AccessDenied (S3 behind its CDN) for gems and quick specs it does not have
        when(proxyService.negativeTtl(proxyRepo)).thenReturn(java.time.Duration.ofMinutes(10));
        when(proxyService.fetch(eq(proxyRepo), eq("gems/nope-1.0.gem"), any(), anyMap())).thenAnswer(inv -> {
            health.recordFailure(proxyRepo, "HTTP 403 for gems/nope-1.0.gem");
            return Optional.empty();
        });
        when(proxyService.fetch(eq(proxyRepo), eq("quick/Marshal.4.8/nope-1.0.gemspec.rz"), any(), anyMap()))
                .thenAnswer(inv -> {
                    health.recordFailure(proxyRepo, "HTTP 403 for quick/Marshal.4.8/nope-1.0.gemspec.rz");
                    return Optional.empty();
                });

        for (int i = 0; i < 5; i++) {
            assertThat(status(get(proxyRepo, "gems/nope-1.0.gem"))).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(status(get(groupRepo, "quick/Marshal.4.8/nope-1.0.gemspec.rz"))).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(health.status(proxyRepo).consecutiveFailures()).isZero();
        assertThat(health.status(proxyRepo).reachable()).isTrue();
        assertThat(negativeCache.contains(proxyRepo.getId(), "gems/nope-1.0.gem")).isTrue();
        assertThat(negativeCache.contains(proxyRepo.getId(), "quick/Marshal.4.8/nope-1.0.gemspec.rz")).isTrue();
    }

    @Test
    void otherUpstreamFailuresStillCountAsAnOutage() throws IOException {
        when(proxyService.fetch(eq(proxyRepo), eq("gems/broken-1.0.gem"), any(), anyMap())).thenAnswer(inv -> {
            health.recordFailure(proxyRepo, "HTTP 502 for gems/broken-1.0.gem");
            return Optional.empty();
        });
        when(proxyService.fetch(eq(proxyRepo), eq("info/private"), any(), anyMap())).thenAnswer(inv -> {
            health.recordFailure(proxyRepo, "HTTP 403 for info/private");
            return Optional.empty();
        });

        get(proxyRepo, "gems/broken-1.0.gem");
        get(proxyRepo, "info/private");
        assertThat(health.status(proxyRepo).consecutiveFailures()).isEqualTo(2);
        assertThat(negativeCache.contains(proxyRepo.getId(), "gems/broken-1.0.gem")).isFalse();
        assertThat(negativeCache.contains(proxyRepo.getId(), "info/private")).isFalse();
    }

    @Test
    void proxyServesTheCacheWhenUpstreamIsGone() throws IOException {
        get(proxyRepo, "info/rake");
        get(proxyRepo, "gems/rake-13.4.2.gem");
        upstream.clear();
        assertThat(text(get(proxyRepo, "info/rake"))).isEqualTo(upstreamRakeInfo);
        assertThat(text(get(proxyRepo, "gems/rake-13.4.2.gem"))).isEqualTo("rake gem bytes");
        ResponseEntity<?> missing = get(proxyRepo, "info/rails");
        assertThat(status(missing)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(get(proxyRepo, "gems/rails-8.0.0.gem"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void proxyRewritesGemUriOfTheJsonApi() throws IOException {
        JsonNode gem = JSON.readTree(bytes(get(proxyRepo, "api/v1/gems/rake.json")));
        assertThat(gem.path("gem_uri").asString()).isEqualTo(baseUrl(proxyRepo) + "/gems/rake-13.4.2.gem");
        assertThat(gem.path("downloads").asInt()).isEqualTo(5);
        assertThat(JSON.readTree(bytes(get(proxyRepo, "api/v1/versions/rake.json")))).hasSize(2);
        assertThat(text(get(proxyRepo, "api/v1/versions/rake/latest.json"))).isEqualTo("{\"version\":\"13.4.2\"}");

        upstream("api/v1/gems/broken.json", "{not json");
        assertThat(status(get(proxyRepo, "api/v1/gems/broken.json"))).isEqualTo(HttpStatus.NOT_FOUND);
        upstream("api/v1/versions/evil/latest.json", "{\"version\":\"../../x\"}");
        assertThat(status(get(proxyRepo, "api/v1/versions/evil/latest.json"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void proxiesAreReadOnly() throws IOException {
        ResponseEntity<?> response = push(proxyRepo, gem("x", "1.0"));
        assertThat(status(response)).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(text(response)).contains("'gems-proxy' is a proxy repository");
        assertThat(status(form(proxyRepo, "DELETE", "api/v1/gems/yank", "gem_name=rake&version=13.4.2")))
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    // --- group: compact index -------------------------------------------------------------------------

    @Test
    void groupVersionsAppendHostedGemsToTheUpstreamFile() throws IOException {
        push(hostedRepo, resource("acme_hello-0.1.0.gem"));
        push(hostedRepo, gem("rake", "99.0"));

        String merged = text(get(groupRepo, "versions"));
        String upstreamVersions = new String(upstream.get("versions"), StandardCharsets.UTF_8);
        assertThat(merged).startsWith(upstreamVersions);
        String tail = merged.substring(upstreamVersions.length());
        String groupRakeInfo = text(get(groupRepo, "info/rake"));
        String groupAcmeInfo = text(get(groupRepo, "info/acme_hello"));
        assertThat(tail).isEqualTo("acme_hello 0.1.0 " + CompactIndex.md5(groupAcmeInfo) + "\n"
                + "rake 99.0 " + CompactIndex.md5(groupRakeInfo) + "\n");

        Map<String, CompactIndex.Entry> parsed = CompactIndex.parseVersions(new ByteArrayInputStream(
                merged.getBytes(StandardCharsets.UTF_8)));
        assertThat(parsed.get("rake").versions()).containsExactly("13.4.1", "13.4.2", "99.0");
        assertThat(parsed.get("rake").md5()).as("Bundler only refetches info whose checksum differs")
                .isEqualTo(CompactIndex.md5(groupRakeInfo));
    }

    @Test
    void groupInfoListsHostedVersionsFirst() throws IOException {
        push(hostedRepo, gem("rake", "13.4.2"));
        push(hostedRepo, gem("rake", "99.0"));
        List<String> lines = CompactIndex.lines(text(get(groupRepo, "info/rake")));
        assertThat(lines).extracting(CompactIndex::lineKey).containsExactly("13.4.2", "99.0", "13.4.1");
        assertThat(lines.getFirst()).as("the hosted 13.4.2 shadows upstream's").doesNotContain(SHA_B);
    }

    @Test
    void groupServesASingleMembersDocumentAsIs() throws IOException {
        ResponseEntity<?> info = get(groupRepo, "info/rake");
        assertThat(text(info)).isEqualTo(upstreamRakeInfo);
        assertThat(info.getHeaders().getETag()).isEqualTo("\"" + CompactIndex.md5(upstreamRakeInfo) + "\"");

        push(hostedRepo, gem("only_hosted", "1.0"));
        assertThat(text(get(groupRepo, "info/only_hosted"))).isEqualTo(text(get(hostedRepo, "info/only_hosted")));
        assertThat(status(get(groupRepo, "info/nowhere"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void groupNamesAreTheUnion() throws IOException {
        push(hostedRepo, gem("acme_hello", "1.0"));
        assertThat(text(get(groupRepo, "names"))).isEqualTo("---\nacme_hello\nfiller0\nrake\n");
    }

    @Test
    void groupDocumentsAreRebuiltOnlyWhenAMemberChanges() throws IOException {
        push(hostedRepo, gem("acme_hello", "1.0"));
        String first = text(get(groupRepo, "versions"));
        int stores = storage.stores.size();
        assertThat(text(get(groupRepo, "versions"))).isEqualTo(first);
        assertThat(storage.stores.stream().skip(stores).filter(s -> s.startsWith("gems:")).toList())
                .as("the merged versions file is reused").isEmpty();

        push(hostedRepo, gem("acme_hello", "2.0"));
        String second = text(get(groupRepo, "versions"));
        assertThat(second).isNotEqualTo(first).contains("acme_hello 1.0,2.0 ");
        assertThat(storage.find(groupRepo, "versions")).isPresent();
    }

    @Test
    void groupCompactIndexWorksWithoutTheProxy() throws IOException {
        upstream.clear();
        push(hostedRepo, gem("acme_hello", "1.0"));
        assertThat(text(get(groupRepo, "versions"))).isEqualTo(text(get(hostedRepo, "versions")));
        assertThat(text(get(groupRepo, "names"))).isEqualTo("---\nacme_hello\n");
    }

    // --- group: marshal indexes -----------------------------------------------------------------------

    @Test
    void groupSpecsAppendHostedTuplesToUpstreamsIndex() throws IOException {
        push(hostedRepo, gem("acme_hello", "1.0"));
        push(hostedRepo, gem("alpha", "2.0"));
        push(hostedRepo, gem("acme_hello", "2.0.beta"));
        push(hostedRepo, platformGem("acme_native", "0.1", "x86_64-linux"));

        List<SpecTuple> specs = specs(get(groupRepo, "specs.4.8.gz"));
        List<SpecTuple> ruby = SpecsIndex.read(new ByteArrayInputStream(resource("ruby-specs.4.8.gz")));
        assertThat(specs.subList(0, ruby.size())).isEqualTo(ruby);
        assertThat(specs.subList(ruby.size(), specs.size())).containsExactly(new SpecTuple("acme_hello", "1.0", "ruby"),
                new SpecTuple("acme_native", "0.1", "x86_64-linux"));

        assertThat(specs(get(groupRepo, "latest_specs.4.8.gz"))).containsExactly(new SpecTuple("acme_hello", "1.0", "ruby"),
                new SpecTuple("acme_native", "0.1", "x86_64-linux"), new SpecTuple("alpha", "2.0", "ruby"),
                new SpecTuple("rake", "13.4.2", "ruby"));
        assertThat(specs(get(groupRepo, "prerelease_specs.4.8.gz"))).containsExactlyInAnyOrder(
                new SpecTuple("alpha", "3.0.rc1", "ruby"), new SpecTuple("acme_hello", "2.0.beta", "ruby"));
    }

    @Test
    void aCorruptUpstreamIndexIsLeftOutOfTheMerge() throws IOException {
        upstream("specs.4.8.gz", "this is not gzip, but it is long enough to be the largest member".repeat(10)
                .getBytes(StandardCharsets.UTF_8));
        upstream("latest_specs.4.8.gz", RubygemsTestSupport.gzip(new byte[] {4, 8, '0'}));
        push(hostedRepo, gem("acme_hello", "1.0"));
        assertThat(specs(get(groupRepo, "specs.4.8.gz"))).containsExactly(new SpecTuple("acme_hello", "1.0", "ruby"));
        assertThat(specs(get(groupRepo, "latest_specs.4.8.gz"))).containsExactly(new SpecTuple("acme_hello", "1.0", "ruby"));
    }

    // --- group: files and writes ----------------------------------------------------------------------

    @Test
    void groupFilesComeFromTheFirstMemberThatHasThem() throws IOException {
        byte[] hostedGem = gem("rake", "13.4.2");
        push(hostedRepo, hostedGem);
        assertThat(bytes(get(groupRepo, "gems/rake-13.4.2.gem"))).isEqualTo(hostedGem);
        assertThat(fetches).doesNotContain("gems/rake-13.4.2.gem");
        assertThat(text(get(groupRepo, "gems/rake-13.4.2.gem"))).isNotEqualTo("rake gem bytes");

        upstream("gems/other-1.0.gem", "other".getBytes(StandardCharsets.UTF_8));
        assertThat(text(get(groupRepo, "gems/other-1.0.gem"))).isEqualTo("other");
        assertThat(QuickSpec.read(bytes(get(groupRepo, "quick/Marshal.4.8/rake-13.4.2.gemspec.rz"))).version())
                .isEqualTo("13.4.2");
        assertThat(status(get(groupRepo, "gems/missing-1.0.gem"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void groupsSendWritesToTheirHostedMember() throws IOException {
        ResponseEntity<?> response = push(groupRepo, gem("x", "1.0"));
        assertThat(status(response)).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(text(response)).contains("'gems' is a group repository").contains("'gems-hosted'")
                .contains("gem push --host <server>/repository/gems-hosted");
        ResponseEntity<?> unyank = form(groupRepo, "PUT", "api/v1/gems/unyank", "gem_name=x&version=1.0");
        assertThat(status(unyank)).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(text(unyank)).contains("gem yank --host <server>/repository/gems-hosted");
        ResponseEntity<?> yank = form(groupRepo, "DELETE", "api/v1/gems/yank", "gem_name=x&version=1.0");
        assertThat(status(yank)).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(text(yank)).contains("you cannot yank from it").contains("gem yank --host");

        Repository proxiesOnly = RubygemsTestSupport.group("mirrors", "gems-proxy");
        when(groups.leafMembers(proxiesOnly)).thenReturn(List.of(proxyRepo));
        assertThat(text(push(proxiesOnly, gem("x", "1.0")))).contains("it has no hosted member");
    }

    // --- group: JSON API ------------------------------------------------------------------------------

    @Test
    void groupJsonApiPrefersHostedAndMergesVersionLists() throws IOException {
        push(hostedRepo, gem("rake", "99.0"));
        JsonNode gem = JSON.readTree(bytes(get(groupRepo, "api/v1/gems/rake.json")));
        assertThat(gem.path("version").asString()).isEqualTo("99.0");
        assertThat(gem.path("gem_uri").asString()).isEqualTo(baseUrl(groupRepo) + "/gems/rake-99.0.gem");

        JsonNode versions = JSON.readTree(bytes(get(groupRepo, "api/v1/versions/rake.json")));
        List<String> numbers = new ArrayList<>();
        versions.forEach(v -> numbers.add(v.path("number").asString()));
        assertThat(numbers).containsExactly("99.0", "13.4.2", "13.4.1");

        assertThat(text(get(groupRepo, "api/v1/versions/rake/latest.json"))).isEqualTo("{\"version\":\"99.0\"}");
    }

    @Test
    void storageCoordinatesOfHostedGemsUseTheVersionKey() throws IOException {
        push(hostedRepo, platformGem("native", "1.0", "java"));
        assertThat(storage.coordinates.values()).contains(new StorageService.ComponentCoordinates("", "native", "1.0-java"));
    }

    private static List<SpecTuple> specs(ResponseEntity<?> response) throws IOException {
        assertThat(status(response)).isEqualTo(HttpStatus.OK);
        return SpecsIndex.read(new ByteArrayInputStream(bytes(response)));
    }
}
