package dev.granary.server.rubygems;

import static dev.granary.server.rubygems.RubygemsFixtures.gem;
import static dev.granary.server.rubygems.RubygemsFixtures.inflate;
import static dev.granary.server.rubygems.RubygemsFixtures.lines;
import static dev.granary.server.rubygems.RubygemsFixtures.md5;
import static dev.granary.server.rubygems.RubygemsFixtures.platformGem;
import static dev.granary.server.rubygems.RubygemsFixtures.repositoryUrl;
import static dev.granary.server.rubygems.RubygemsFixtures.sha256;
import static dev.granary.server.rubygems.RubygemsFixtures.specs;
import static dev.granary.server.rubygems.RubygemsFixtures.versions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

/** {@code gem push}, {@code gem install}, Bundler and {@code gem yank} against a hosted repository, over HTTP. */
class RubygemsHostedIntegrationTest extends IntegrationTest {

    private static final String REPO = "gems-hosted";
    private static final String URL = "/repository/" + REPO;

    @Autowired
    private StorageService storage;

    private Repository repo;

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "rubygems");
    }

    private ResultActions push(byte[] gem) throws Exception {
        return mvc.perform(post(URL + "/api/v1/gems").header("Authorization", adminBasic())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(gem));
    }

    private MockHttpServletResponse fetch(String path) throws Exception {
        return mvc.perform(get(URL + "/" + path)).andExpect(status().isOk()).andReturn().getResponse();
    }

    private String text(String path) throws Exception {
        return fetch(path).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void pushedGemsAreInstallableThroughTheCompactIndex() throws Exception {
        byte[] gem = gem("acme_hello", "0.1.0", "rake:>= 12.0");
        push(gem).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Successfully registered gem: acme_hello (0.1.0)"));

        // what Bundler reads: versions, then info/<gem> when its checksum changed
        String versions = text("versions");
        String info = text("info/acme_hello");
        assertThat(versions).startsWith("created_at: ");
        assertThat(versions(versions).get("acme_hello")).containsExactly("0.1.0", md5(info));
        assertThat(lines(info)).singleElement().asString()
                .startsWith("0.1.0 rake:>= 12.0|checksum:" + sha256(gem) + ",ruby:>= 2.7,created_at:");
        assertThat(text("names")).isEqualTo("---\nacme_hello\n");

        MockHttpServletResponse download = fetch("gems/acme_hello-0.1.0.gem");
        assertThat(download.getContentAsByteArray()).isEqualTo(gem);
        assertThat(download.getContentType()).isEqualTo("application/octet-stream");
        assertThat(download.getHeader("ETag")).isEqualTo("\"" + sha256(gem) + "\"");
        assertThat(storage.find(repo, "gems/acme_hello-0.1.0.gem")).hasValueSatisfying(c -> {
            assertThat(c.asset().getDownloadCount()).isEqualTo(1);
            assertThat(c.asset().getOrigin()).isEqualTo(Asset.Origin.UPLOADED);
            assertThat(c.asset().getComponentId()).isNotNull();
        });
    }

    @Test
    void pushedGemsAreInstallableThroughTheMarshalIndexes() throws Exception {
        push(gem("acme_hello", "0.1.0")).andExpect(status().isOk());
        push(gem("acme_hello", "0.2.0.beta")).andExpect(status().isOk());
        push(platformGem("acme_native", "1.0.0", "x86_64-linux")).andExpect(status().isOk());

        // what gem install --source reads when there is no compact index
        assertThat(specs(fetch("specs.4.8.gz").getContentAsByteArray()))
                .containsExactly("acme_hello 0.1.0 ruby", "acme_native 1.0.0 x86_64-linux");
        assertThat(specs(fetch("latest_specs.4.8.gz").getContentAsByteArray()))
                .containsExactly("acme_hello 0.1.0 ruby", "acme_native 1.0.0 x86_64-linux");
        assertThat(specs(fetch("prerelease_specs.4.8.gz").getContentAsByteArray()))
                .containsExactly("acme_hello 0.2.0.beta ruby");

        MockHttpServletResponse quick = fetch("quick/Marshal.4.8/acme_native-1.0.0-x86_64-linux.gemspec.rz");
        String marshalled = new String(inflate(quick.getContentAsByteArray()), StandardCharsets.ISO_8859_1);
        assertThat(marshalled).startsWith("\u0004\bu:\u0017Gem::Specification").contains("acme_native").contains("x86_64-linux");
        assertThat(quick.getHeader(HttpHeaders.CACHE_CONTROL)).contains("max-age=31536000");
    }

    @Test
    void theCompactIndexSupportsBundlersIncrementalUpdates() throws Exception {
        push(gem("acme_hello", "0.1.0")).andExpect(status().isOk());
        MockHttpServletResponse first = fetch("versions");
        byte[] before = first.getContentAsByteArray();
        String etag = first.getHeader("ETag");
        assertThat(etag).isEqualTo("\"" + RubygemsFixtures.hex("MD5", before) + "\"");
        assertThat(first.getHeader("Repr-Digest")).isEqualTo("sha-256=:"
                + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256(before))) + ":");
        assertThat(first.getHeader("Accept-Ranges")).isEqualTo("bytes");
        assertThat(first.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache");

        // unchanged: 304
        mvc.perform(get(URL + "/versions").header("If-None-Match", etag)).andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag));

        // Bundler asks for everything from its last byte on
        mvc.perform(get(URL + "/versions").header("Range", "bytes=" + (before.length - 1) + "-")
                        .header("If-None-Match", etag))
                .andExpect(status().isNotModified());
        MockHttpServletResponse tail = mvc.perform(get(URL + "/versions").header("Range", "bytes=" + (before.length - 1) + "-"))
                .andExpect(status().isPartialContent()).andReturn().getResponse();
        assertThat(tail.getContentAsByteArray()).containsExactly('\n');
        assertThat(tail.getHeader("Content-Range")).isEqualTo("bytes " + (before.length - 1) + "-" + (before.length - 1)
                + "/" + before.length);

        // a range beyond the end: Bundler falls back to a full download
        mvc.perform(get(URL + "/versions").header("Range", "bytes=" + (before.length + 100) + "-"))
                .andExpect(status().isRequestedRangeNotSatisfiable());

        // HEAD carries the length, as gem install --source uses it to probe for the compact index
        mvc.perform(head(URL + "/versions")).andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", before.length))
                .andExpect(content().bytes(new byte[0]));

        push(gem("acme_hello", "0.2.0")).andExpect(status().isOk());
        String after = text("versions");
        assertThat(versions(after).get("acme_hello")).containsExactly("0.1.0", "0.2.0", md5(text("info/acme_hello")));
        mvc.perform(get(URL + "/versions").header("If-None-Match", etag)).andExpect(status().isOk());
    }

    @Test
    void aVersionCannotBePushedTwice() throws Exception {
        byte[] gem = gem("acme_hello", "1.0.0");
        push(gem).andExpect(status().isOk());
        push(gem).andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("Repushing of gem versions is not allowed.")));

        repo.setAllowRedeploy(true);
        repositories.update(REPO, repo);
        push(gem("acme_hello", "1.0.0", "rake:>= 13")).andExpect(status().isOk());
        assertThat(text("info/acme_hello")).contains("1.0.0 rake:>= 13|");
    }

    @Test
    void invalidGemsAreRefusedWithAMessageGemPrints() throws Exception {
        push("not a gem".repeat(100).getBytes(StandardCharsets.UTF_8))
                .andExpect(status().is(422))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("RubyGems cannot process this gem.")));
        push(new byte[0]).andExpect(status().isBadRequest());

        repo.setFormatSettings(java.util.Map.of("rubygems.maxGemSizeMb", "1"));
        repositories.update(REPO, repo);
        push(new byte[1024 * 1024 + 10]).andExpect(status().is(413));
        assertThat(storage.list(repo, "")).isEmpty();
    }

    @Test
    void yankAndUnyankAsGemSendsThem() throws Exception {
        push(gem("acme_hello", "0.1.0")).andExpect(status().isOk());
        push(gem("acme_hello", "0.2.0")).andExpect(status().isOk());

        // gem yank: DELETE with a form body
        mvc.perform(delete(URL + "/api/v1/gems/yank").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("gem_name=acme_hello&version=0.2.0"))
                .andExpect(status().isOk())
                .andExpect(content().string("Successfully deleted gem: acme_hello (0.2.0)"));
        assertThat(versions(text("versions")).get("acme_hello")).containsExactly("0.1.0", md5(text("info/acme_hello")));
        assertThat(text("info/acme_hello")).doesNotContain("0.2.0");
        assertThat(specs(fetch("specs.4.8.gz").getContentAsByteArray())).containsExactly("acme_hello 0.1.0 ruby");
        mvc.perform(get(URL + "/api/v1/versions/acme_hello/latest.json")).andExpect(jsonPath("$.version").value("0.1.0"));

        mvc.perform(delete(URL + "/api/v1/gems/yank").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("gem_name=acme_hello&version=0.2.0"))
                .andExpect(status().is(422));

        mvc.perform(put(URL + "/api/v1/gems/unyank").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("gem_name=acme_hello&version=0.2.0"))
                .andExpect(status().isOk())
                .andExpect(content().string("Successfully reinstated gem: acme_hello (0.2.0)"));
        assertThat(versions(text("versions")).get("acme_hello")).startsWith("0.1.0", "0.2.0");

        mvc.perform(delete(URL + "/api/v1/gems/yank").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("gem_name=missing&version=1"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("This rubygem could not be found."));
    }

    @Test
    void deletingAComponentRemovesItFromTheIndexes() throws Exception {
        push(gem("acme_hello", "0.1.0")).andExpect(status().isOk());
        push(gem("acme_hello", "0.2.0")).andExpect(status().isOk());
        assertThat(versions(text("versions")).get("acme_hello")).startsWith("0.1.0", "0.2.0");

        storage.list(repo, "").stream()
                .filter(a -> a.getPath().contains("acme_hello-0.2.0"))
                .forEach(a -> storage.delete(repo, a.getPath()));

        assertThat(versions(text("versions")).get("acme_hello")).containsExactly("0.1.0", md5(text("info/acme_hello")));
        mvc.perform(get(URL + "/gems/acme_hello-0.2.0.gem")).andExpect(status().isNotFound());
    }

    @Test
    void theJsonApiDescribesPushedGems() throws Exception {
        push(gem("acme_hello", "0.1.0", "rake:>= 12.0")).andExpect(status().isOk());
        mvc.perform(get(URL + "/api/v1/gems/acme_hello.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("acme_hello"))
                .andExpect(jsonPath("$.version").value("0.1.0"))
                .andExpect(jsonPath("$.gem_uri").value(repositoryUrl(REPO) + "/gems/acme_hello-0.1.0.gem"))
                .andExpect(jsonPath("$.dependencies.runtime[0].name").value("rake"))
                .andExpect(jsonPath("$.dependencies.runtime[0].requirements").value(">= 12.0"));
        mvc.perform(get(URL + "/api/v1/versions/acme_hello.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value("0.1.0"))
                .andExpect(jsonPath("$[0].platform").value("ruby"));
        mvc.perform(get(URL + "/api/v1/gems/unknown.json")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/api/v1/dependencies").param("gems", "acme_hello")).andExpect(status().isNotFound());
    }

    @Test
    void illegalPathsAndMethodsAreRefused() throws Exception {
        mvc.perform(get(URL + "/info/..%2Fversions")).andExpect(status().is4xxClientError());
        mvc.perform(get(URL + "/nothing-here")).andExpect(status().isNotFound());
        mvc.perform(post(URL + "/versions").header("Authorization", adminBasic())).andExpect(status().isMethodNotAllowed());
        push(gem("acme_hello", "0.1.0")).andExpect(status().isOk());
        mvc.perform(delete(URL + "/gems/acme_hello-0.1.0.gem").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
        fetch("gems/acme_hello-0.1.0.gem");
    }

    @Test
    void theLandingPageShowsTheSourceUrl() throws Exception {
        assertThat(text("")).contains("gem sources --add " + repositoryUrl(REPO) + "/");
    }
}
