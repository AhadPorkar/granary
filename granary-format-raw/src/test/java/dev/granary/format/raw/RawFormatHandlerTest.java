package dev.granary.format.raw;

import static dev.granary.format.raw.RawTestSupport.baseUrl;
import static dev.granary.format.raw.RawTestSupport.body;
import static dev.granary.format.raw.RawTestSupport.header;
import static dev.granary.format.raw.RawTestSupport.hosted;
import static dev.granary.format.raw.RawTestSupport.json;
import static dev.granary.format.raw.RawTestSupport.multipart;
import static dev.granary.format.raw.RawTestSupport.properties;
import static dev.granary.format.raw.RawTestSupport.proxy;
import static dev.granary.format.raw.RawTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.GranaryException;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** The shared raw behaviour, exercised through the generic profile. */
class RawFormatHandlerTest {

    @TempDir
    Path dir;

    final FakeStorage storage = new FakeStorage();
    final ProxyService proxy = mock(ProxyService.class);
    final GroupResolver groups = mock(GroupResolver.class);
    final Repository hosted = hosted("raw-hosted", "raw");
    final Repository remote = proxy("raw-proxy", "raw", "https://files.example.com/");
    RawGenericFormatHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RawGenericFormatHandler(storage.mock, proxy, groups, properties(dir));
    }

    @Test
    void identityTemplatesAndSettings() {
        assertThat(handler.format()).isEqualTo("raw");
        assertThat(handler.displayName()).isEqualTo("Raw (generic files)");
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder(RepositoryType.HOSTED, RepositoryType.PROXY, RepositoryType.GROUP);
        assertThat(handler.templates()).extracting(RepositoryTemplate::name).containsExactly("raw-hosted");
        assertThat(handler.templates().get(0).type()).isEqualTo(RepositoryType.HOSTED);
        assertThat(handler.settings()).extracting(SettingDescriptor::key)
                .containsExactlyInAnyOrder("raw.metadataPatterns", "raw.contentDisposition", "raw.listingEnabled");
        SettingDescriptor patterns = handler.settings().stream().filter(s -> s.key().equals("raw.metadataPatterns")).findFirst().orElseThrow();
        assertThat(patterns.defaultValue()).contains("**/Packages").contains("**/repodata/**");
    }

    @Test
    void validateChecksTheSharedSettings() {
        handler.validate(hosted);
        hosted.setFormatSettings(Map.of("raw.contentDisposition", "download"));
        assertThatThrownBy(() -> handler.validate(hosted))
                .isInstanceOf(GranaryException.class)
                .extracting(e -> ((GranaryException) e).code()).isEqualTo("repository.raw.contentDisposition");
        hosted.setFormatSettings(Map.of("raw.listingEnabled", "maybe"));
        assertThatThrownBy(() -> handler.validate(hosted)).isInstanceOf(GranaryException.class);
        hosted.setFormatSettings(Map.of("raw.metadataPatterns", "a\\b"));
        assertThatThrownBy(() -> handler.validate(hosted)).isInstanceOf(GranaryException.class);
    }

    @Test
    void defaultMetadataPatternsCoverAptStyleIndexesAndLatestLinks() {
        assertThat(handler.isMetadataPath("dists/stable/Release")).isTrue();
        assertThat(handler.isMetadataPath("dists/stable/main/binary-amd64/Packages.gz")).isTrue();
        assertThat(handler.isMetadataPath("el9/repodata/repomd.xml")).isTrue();
        assertThat(handler.isMetadataPath("app/latest/app.zip")).isTrue();
        assertThat(handler.isMetadataPath("docs/index.html")).isTrue();
        assertThat(handler.isMetadataPath("app/1.2.3/app.zip")).isFalse();
        assertThat(handler.isMetadataPath("pool/main/a/app_1.2.3_amd64.deb")).isFalse();
    }

    @Test
    void putThenGetRoundTrip() throws Exception {
        byte[] bytes = "zip-bytes".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<?> put = handler.handle(request(hosted, "PUT", "releases/app/1.0/app.zip", bytes, "application/zip"));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = json(put);
        assertThat(created.path("path").asString()).isEqualTo("releases/app/1.0/app.zip");
        assertThat(created.path("size").asLong()).isEqualTo(bytes.length);
        assertThat(created.path("sha256").asString()).hasSize(64);

        Asset asset = storage.asset(hosted, "releases/app/1.0/app.zip");
        assertThat(asset.getContentType()).isEqualTo("application/zip");
        assertThat(asset.isMetadata()).isFalse();
        assertThat(asset.getOrigin()).isEqualTo(Asset.Origin.UPLOADED);
        assertThat(storage.coordinates(hosted, "releases/app/1.0/app.zip"))
                .isEqualTo(new StorageService.ComponentCoordinates("", "releases/app/1.0", "app.zip"));

        ResponseEntity<?> get = handler.handle(request(hosted, "GET", "releases/app/1.0/app.zip"));
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(get)).isEqualTo("zip-bytes");
        assertThat(get.getHeaders().getContentType()).hasToString("application/zip");
        assertThat(header(get, "ETag")).contains(asset.getBlobSha256());
        assertThat(header(get, "X-Checksum-Sha256")).isEqualTo(asset.getBlobSha256());
        assertThat(header(get, "Cache-Control")).contains("max-age");
        assertThat(header(get, "Content-Disposition")).startsWith("inline; filename=");
        assertThat(storage.downloads).containsExactly("releases/app/1.0/app.zip");

        ResponseEntity<?> head = handler.handle(request(hosted, "HEAD", "releases/app/1.0/app.zip"));
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isEqualTo(bytes.length);
        assertThat(storage.downloads).hasSize(1);

        ResponseEntity<?> cached = handler.handle(request(hosted, "GET", "releases/app/1.0/app.zip", null, null,
                Map.of("If-None-Match", header(get, "ETag"))));
        assertThat(cached.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(cached.getBody()).isNull();

        assertThat(handler.handle(request(hosted, "GET", "releases/app/1.0/missing.zip")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void uninformativeContentTypesAreReplacedByAGuess() throws Exception {
        handler.handle(request(hosted, "PUT", "notes/readme.txt", "hi".getBytes(StandardCharsets.UTF_8), "application/octet-stream"));
        handler.handle(request(hosted, "PUT", "notes/data.bin", "hi".getBytes(StandardCharsets.UTF_8), "application/octet-stream"));
        handler.handle(request(hosted, "PUT", "notes/custom.xyz", "hi".getBytes(StandardCharsets.UTF_8), "application/x-custom; charset=utf-8"));
        handler.handle(request(hosted, "PUT", "notes/form.txt", "hi".getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded"));
        assertThat(storage.asset(hosted, "notes/readme.txt").getContentType()).isEqualTo("text/plain");
        assertThat(storage.asset(hosted, "notes/data.bin").getContentType()).isEqualTo("application/octet-stream");
        assertThat(storage.asset(hosted, "notes/custom.xyz").getContentType()).isEqualTo("application/x-custom");
        assertThat(storage.asset(hosted, "notes/form.txt").getContentType()).isEqualTo("text/plain");
    }

    @Test
    void contentTypesTooLongToStoreAreReplacedByAGuess() throws Exception {
        String overlong = "application/" + "x".repeat(250);
        assertThat(handler.handle(request(hosted, "PUT", "notes/long.txt", "hi".getBytes(StandardCharsets.UTF_8), overlong))
                .getStatusCode().value()).isEqualTo(201);
        assertThat(storage.asset(hosted, "notes/long.txt").getContentType()).isEqualTo("text/plain");
        String longest = "application/" + "x".repeat(RawFormatHandler.MAX_CONTENT_TYPE - "application/".length());
        handler.handle(request(hosted, "PUT", "notes/fits.bin", "hi".getBytes(StandardCharsets.UTF_8), longest));
        assertThat(storage.asset(hosted, "notes/fits.bin").getContentType()).isEqualTo(longest);
    }

    @Test
    void filesWhoseComponentWouldNotFitTheTableAreStoredWithoutOne() throws Exception {
        String deepDirectory = String.join("/", java.util.Collections.nCopies(6, "d".repeat(99)));
        String longFileName = "f".repeat(250) + ".txt";
        assertThat(handler.handle(request(hosted, "PUT", deepDirectory + "/x.txt", "a")).getStatusCode().value()).isEqualTo(201);
        assertThat(handler.handle(request(hosted, "PUT", "dir/" + longFileName, "b")).getStatusCode().value()).isEqualTo(201);
        assertThat(storage.coordinates(hosted, deepDirectory + "/x.txt")).isNull();
        assertThat(storage.coordinates(hosted, "dir/" + longFileName)).isNull();
        assertThat(body(handler.handle(request(hosted, "GET", "dir/" + longFileName)))).isEqualTo("b");
        // the redeploy rule still holds without a component to lock on
        assertThat(handler.handle(request(hosted, "PUT", "dir/" + longFileName, "c")).getStatusCode().value()).isEqualTo(409);
        assertThat(storage.coordinates(hosted, "dir/short.txt")).isNull();
        handler.handle(request(hosted, "PUT", "dir/short.txt", "d"));
        assertThat(storage.coordinates(hosted, "dir/short.txt").name()).isEqualTo("dir");
    }

    @Test
    void metadataFlagFollowsThePatternsOfTheRepository() throws Exception {
        handler.handle(request(hosted, "PUT", "dists/stable/Release", "v1"));
        handler.handle(request(hosted, "PUT", "pool/app.deb", "v1"));
        assertThat(storage.asset(hosted, "dists/stable/Release").isMetadata()).isTrue();
        assertThat(storage.asset(hosted, "pool/app.deb").isMetadata()).isFalse();
        ResponseEntity<?> release = handler.handle(request(hosted, "GET", "dists/stable/Release"));
        assertThat(header(release, "Cache-Control")).contains("no-cache");

        Repository custom = hosted("raw-custom", "raw");
        custom.setFormatSettings(Map.of("raw.metadataPatterns", "pool/**"));
        handler.handle(request(custom, "PUT", "dists/stable/Release", "v1"));
        handler.handle(request(custom, "PUT", "pool/app.deb", "v1"));
        assertThat(storage.asset(custom, "dists/stable/Release").isMetadata()).isFalse();
        assertThat(storage.asset(custom, "pool/app.deb").isMetadata()).isTrue();
    }

    @Test
    void overwriteIsRefusedUnlessRedeployIsAllowed() throws Exception {
        assertThat(handler.handle(request(hosted, "PUT", "a/b.txt", "v1")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<?> again = handler.handle(request(hosted, "PUT", "a/b.txt", "v2"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(again).path("code").asString()).isEqualTo("repository.raw.exists");
        assertThat(storage.text(hosted, "a/b.txt")).isEqualTo("v1");

        hosted.setAllowRedeploy(true);
        assertThat(handler.handle(request(hosted, "PUT", "a/b.txt", "v2")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.text(hosted, "a/b.txt")).isEqualTo("v2");
    }

    @Test
    void illegalPathsAndMethodsAreRejected() throws Exception {
        ResponseEntity<?> traversal = handler.handle(request(hosted, "PUT", "a/../b.txt", "x"));
        assertThat(traversal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(traversal).path("code").asString()).isEqualTo("path.invalid");
        assertThat(handler.handle(request(hosted, "GET", "a\\b.txt")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handle(request(hosted, "GET", "a/\u0001b.txt")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<?> directory = handler.handle(request(hosted, "PUT", "a/dir/", "x"));
        assertThat(directory.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(directory).path("code").asString()).isEqualTo("path.invalid");
        ResponseEntity<?> patch = handler.handle(request(hosted, "PATCH", "a/b.txt", "x"));
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(storage.paths(hosted)).isEmpty();
    }

    @Test
    void encodedSlashesAndBackslashesInTheRequestUriAreRejected() throws Exception {
        for (String raw : List.of("dir%2Fy.txt", "dir%2fy.txt", "dir%5Cy.txt", "a/b%2F..%2Fc.txt")) {
            org.springframework.mock.web.MockHttpServletRequest http =
                    new org.springframework.mock.web.MockHttpServletRequest("PUT", "/repository/raw-hosted/" + raw);
            http.setContent("x".getBytes(StandardCharsets.UTF_8));
            String decoded = java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
            ResponseEntity<?> response = handler.handle(new dev.granary.core.format.RepositoryRequest(hosted, decoded, http,
                    RawTestSupport.anonymous(), baseUrl(hosted)));
            assertThat(response.getStatusCode()).as(raw).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(response).path("code").asString()).isEqualTo("path.invalid");
        }
        assertThat(storage.paths(hosted)).isEmpty();
        // percent-encoded characters that are not separators are fine
        org.springframework.mock.web.MockHttpServletRequest http =
                new org.springframework.mock.web.MockHttpServletRequest("PUT", "/repository/raw-hosted/dir/a%20b%3F.txt");
        http.setContent("x".getBytes(StandardCharsets.UTF_8));
        ResponseEntity<?> ok = handler.handle(new dev.granary.core.format.RepositoryRequest(hosted, "dir/a b?.txt", http,
                RawTestSupport.anonymous(), baseUrl(hosted)));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.paths(hosted)).containsExactly("dir/a b?.txt");
    }

    @Test
    void multipartPostKeepsEveryFileOfARepeatedField() throws Exception {
        // curl -F file=@app.zip -F file=@app.zip.sha256 <dir>/ sends two parts named "file"
        ResponseEntity<?> post = handler.handle(multipart(hosted, "POST", "releases/1.0/",
                new MockMultipartFile("file", "app.zip", "application/zip", "zip".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("file", "app.zip.sha256", "text/plain", "sum".getBytes(StandardCharsets.UTF_8))));
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(post).path("stored")).hasSize(2);
        assertThat(storage.text(hosted, "releases/1.0/app.zip")).isEqualTo("zip");
        assertThat(storage.text(hosted, "releases/1.0/app.zip.sha256")).isEqualTo("sum");
    }

    @Test
    void multipartPutStoresTheFirstFilePart() throws Exception {
        ResponseEntity<?> put = handler.handle(multipart(hosted, "PUT", "docs/manual.pdf",
                new MockMultipartFile("file", "manual.pdf", "application/pdf", "pdf-bytes".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("other", "notes.txt", "text/plain", "ignored".getBytes(StandardCharsets.UTF_8))));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.text(hosted, "docs/manual.pdf")).isEqualTo("pdf-bytes");
        assertThat(storage.asset(hosted, "docs/manual.pdf").getContentType()).isEqualTo("application/pdf");
        assertThat(storage.paths(hosted)).containsExactly("docs/manual.pdf");
    }

    @Test
    void multipartPostToADirectoryStoresEveryPartUnderItsOwnName() throws Exception {
        ResponseEntity<?> post = handler.handle(multipart(hosted, "POST", "uploads/",
                new MockMultipartFile("a", "a.txt", "text/plain", "A".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("b", "C:\\tmp\\b.txt", "text/plain", "B".getBytes(StandardCharsets.UTF_8))));
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode stored = json(post).path("stored");
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).path("path").asString()).isEqualTo("uploads/a.txt");
        assertThat(stored.get(1).path("path").asString()).isEqualTo("uploads/b.txt");
        assertThat(storage.text(hosted, "uploads/b.txt")).isEqualTo("B");

        ResponseEntity<?> empty = handler.handle(multipart(hosted, "POST", "uploads/"));
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(empty).path("code").asString()).isEqualTo("upload.empty");

        ResponseEntity<?> plain = handler.handle(request(hosted, "POST", "uploads/", "x"));
        assertThat(plain.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(plain).path("code").asString()).isEqualTo("path.invalid");

        ResponseEntity<?> conflict = handler.handle(multipart(hosted, "POST", "uploads/",
                new MockMultipartFile("a", "a.txt", "text/plain", "A2".getBytes(StandardCharsets.UTF_8))));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void postToAFilePathBehavesLikePut() throws Exception {
        ResponseEntity<?> post = handler.handle(request(hosted, "POST", "x/y.txt", "payload"));
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.text(hosted, "x/y.txt")).isEqualTo("payload");
        ResponseEntity<?> multipartToFile = handler.handle(multipart(hosted, "POST", "x/z.txt",
                new MockMultipartFile("file", "whatever.txt", "text/plain", "Z".getBytes(StandardCharsets.UTF_8))));
        assertThat(multipartToFile.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.text(hosted, "x/z.txt")).isEqualTo("Z");
    }

    @Test
    void htmlListingEscapesNamesAndLinksToTheParent() throws Exception {
        storage.put(hosted, "releases/<b>.txt", "x");
        storage.put(hosted, "releases/app/1.0/app.zip", "y");
        storage.put(hosted, "other.txt", "z");

        ResponseEntity<?> listing = handler.handle(request(hosted, "GET", "releases/"));
        assertThat(listing.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listing.getHeaders().getContentType()).hasToString("text/html;charset=UTF-8");
        String html = body(listing);
        assertThat(html).contains("&lt;b&gt;.txt").doesNotContain("<b>.txt").contains("href=\"app/\"").contains("../");
        assertThat(html).doesNotContain("other.txt");
        assertThat(html.indexOf("app/")).isLessThan(html.indexOf("&lt;b&gt;.txt"));

        String root = body(handler.handle(request(hosted, "GET", "")));
        assertThat(root).contains("releases/").contains("other.txt").doesNotContain("\"../\"");

        assertThat(handler.handle(request(hosted, "GET", "nothing/")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "HEAD", "releases/")).getBody()).isNull();
    }

    @Test
    void jsonListingWhenTheClientAsksForIt() throws Exception {
        storage.put(hosted, "releases/b.txt", "xx");
        storage.put(hosted, "releases/app/1.0/app.zip", "y");
        ResponseEntity<?> listing = handler.handle(request(hosted, "GET", "releases/", null, null, Map.of("Accept", "application/json")));
        assertThat(listing.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listing.getHeaders().getContentType()).hasToString("application/json");
        JsonNode json = json(listing);
        assertThat(json.path("repository").asString()).isEqualTo("raw-hosted");
        assertThat(json.path("path").asString()).isEqualTo("releases/");
        assertThat(json.path("entries")).hasSize(2);
        assertThat(json.path("entries").get(0).path("name").asString()).isEqualTo("app");
        assertThat(json.path("entries").get(0).path("type").asString()).isEqualTo("directory");
        assertThat(json.path("entries").get(1).path("name").asString()).isEqualTo("b.txt");
        assertThat(json.path("entries").get(1).path("size").asLong()).isEqualTo(2);

        String emptyRoot = body(handler.handle(request(hosted("fresh", "raw"), "GET", "", null, null, Map.of("Accept", "application/json"))));
        assertThat(RawTestSupport.JSON.readTree(emptyRoot).path("entries")).isEmpty();
    }

    @Test
    void listingsCanBeSwitchedOff() throws Exception {
        storage.put(hosted, "releases/b.txt", "xx");
        hosted.setFormatSettings(Map.of("raw.listingEnabled", "false"));
        ResponseEntity<?> listing = handler.handle(request(hosted, "GET", "releases/"));
        assertThat(listing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(listing).path("message").asString()).contains("disabled");
        assertThat(handler.handle(request(hosted, "GET", "releases/b.txt")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void attachmentDispositionIsOptIn() throws Exception {
        storage.put(hosted, "releases/r\u00e9sum\u00e9.pdf", "x");
        hosted.setFormatSettings(Map.of("raw.contentDisposition", "attachment"));
        ResponseEntity<?> get = handler.handle(request(hosted, "GET", "releases/r\u00e9sum\u00e9.pdf"));
        assertThat(header(get, "Content-Disposition")).startsWith("attachment;").contains("filename*=UTF-8''r%C3%A9sum%C3%A9.pdf");
    }

    @Test
    void deleteRemovesFilesAndDirectories() throws Exception {
        storage.put(hosted, "releases/a.txt", "a");
        storage.put(hosted, "releases/sub/b.txt", "b");
        storage.put(hosted, "releases/sub/c.txt", "c");
        storage.put(hosted, "keep.txt", "k");

        assertThat(handler.handle(request(hosted, "DELETE", "releases/a.txt")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(handler.handle(request(hosted, "DELETE", "releases/a.txt")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "DELETE", "releases/sub/")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storage.paths(hosted)).containsExactly("keep.txt");

        ResponseEntity<?> root = handler.handle(request(hosted, "DELETE", ""));
        assertThat(root.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(storage.paths(hosted)).containsExactly("keep.txt");
    }

    @Test
    void proxyFetchesVerbatimWithTheRightMutability() throws Exception {
        when(proxy.fetch(eq(remote), eq("pool/app.deb"), any(FetchOptions.class)))
                .thenReturn(Optional.of(storage.put(remote, "pool/app.deb", "deb".getBytes(StandardCharsets.UTF_8), "application/vnd.debian.binary-package", false)));
        ResponseEntity<?> get = handler.handle(request(remote, "GET", "pool/app.deb"));
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(get)).isEqualTo("deb");
        assertThat(get.getHeaders().getContentType()).hasToString("application/vnd.debian.binary-package");

        ArgumentCaptor<FetchOptions> options = ArgumentCaptor.forClass(FetchOptions.class);
        handler.handle(request(remote, "GET", "dists/stable/Packages"));
        verify(proxy).fetch(eq(remote), eq("dists/stable/Packages"), options.capture());
        assertThat(options.getValue().metadata()).isTrue();
        verify(proxy).fetch(eq(remote), eq("pool/app.deb"), options.capture());
        assertThat(options.getValue().metadata()).isFalse();

        assertThat(handler.handle(request(remote, "GET", "missing.txt")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void writesToAProxyAreRefused() throws Exception {
        ResponseEntity<?> put = handler.handle(request(remote, "PUT", "a.txt", "x"));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(json(put).path("code").asString()).isEqualTo("repository.readOnly");
        assertThat(json(put).path("message").asString()).contains("proxy");
        assertThat(handler.handle(request(remote, "POST", "a.txt", "x")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void groupReadsTheFirstMemberThatHasTheFileAndMergesListings() throws Exception {
        Repository group = RawTestSupport.group("raw-all", "raw", "raw-hosted", "raw-proxy");
        when(groups.leafMembers(group)).thenReturn(List.of(hosted, remote));
        storage.put(hosted, "shared/from-hosted.txt", "hosted");
        storage.put(hosted, "shared/both.txt", "hosted-wins");
        when(proxy.fetch(eq(remote), eq("shared/from-proxy.txt"), any(FetchOptions.class)))
                .thenReturn(Optional.of(storage.put(remote, "shared/from-proxy.txt", "proxy")));
        when(proxy.fetch(eq(remote), eq("shared/both.txt"), any(FetchOptions.class)))
                .thenReturn(Optional.of(storage.put(remote, "shared/both.txt", "proxy-loses")));

        assertThat(body(handler.handle(request(group, "GET", "shared/from-hosted.txt")))).isEqualTo("hosted");
        assertThat(body(handler.handle(request(group, "GET", "shared/from-proxy.txt")))).isEqualTo("proxy");
        assertThat(body(handler.handle(request(group, "GET", "shared/both.txt")))).isEqualTo("hosted-wins");
        assertThat(handler.handle(request(group, "GET", "shared/none.txt")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode listing = json(handler.handle(request(group, "GET", "shared/", null, null, Map.of("Accept", "application/json"))));
        assertThat(listing.path("entries")).extracting(e -> e.path("name").asString())
                .containsExactly("both.txt", "from-hosted.txt", "from-proxy.txt");
        assertThat(listing.path("entries").get(0).path("size").asLong()).isEqualTo("hosted-wins".length());

        ResponseEntity<?> put = handler.handle(request(group, "PUT", "shared/new.txt", "x"));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(json(put).path("message").asString()).contains("hosted member 'raw-hosted'");
        assertThat(handler.handle(request(group, "DELETE", "shared/both.txt")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(storage.paths(hosted)).contains("shared/both.txt");
    }

    @Test
    void uploadsDoNotLeaveSpooledFilesBehind() throws Exception {
        handler.handle(request(hosted, "PUT", "a.txt", "x"));
        Path spool = dir.resolve("tmp").resolve("raw");
        assertThat(!Files.exists(spool) || Files.list(spool).findAny().isEmpty()).isTrue();
    }

    @Test
    void baseUrlOfTheRequestIsTheRepositoryUrl() {
        assertThat(baseUrl(hosted)).isEqualTo("http://granary.test/repository/raw-hosted");
    }
}
