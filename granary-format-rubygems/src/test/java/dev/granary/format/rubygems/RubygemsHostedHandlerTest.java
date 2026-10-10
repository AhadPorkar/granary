package dev.granary.format.rubygems;

import static dev.granary.format.rubygems.RubygemsTestSupport.anonymous;
import static dev.granary.format.rubygems.RubygemsTestSupport.baseUrl;
import static dev.granary.format.rubygems.RubygemsTestSupport.gem;
import static dev.granary.format.rubygems.RubygemsTestSupport.platformGem;
import static dev.granary.format.rubygems.RubygemsTestSupport.request;
import static dev.granary.format.rubygems.RubygemsTestSupport.resource;
import static dev.granary.format.rubygems.RubygemsTestSupport.sha256;
import static dev.granary.format.rubygems.RubygemsTestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.GranaryException;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.ApiToken;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.model.User;
import dev.granary.core.security.ApiTokenService;
import dev.granary.core.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RubygemsHostedHandlerTest extends RubygemsHandlerTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Repository repo = RubygemsTestSupport.hosted("gems-hosted");

    // --- identity, templates, settings, access --------------------------------------------------------

    @Test
    void describesItself() {
        assertThat(handler.format()).isEqualTo("rubygems");
        assertThat(handler.displayName()).isEqualTo("RubyGems");
        assertThat(handler.templates()).extracting(RepositoryTemplate::name)
                .containsExactly("rubygems-proxy", "rubygems-hosted", "rubygems-group");
        RepositoryTemplate proxy = handler.templates().getFirst();
        assertThat(proxy.type()).isEqualTo(RepositoryType.PROXY);
        assertThat(proxy.remoteUrl()).isEqualTo("https://rubygems.org/");
        assertThat(handler.templates().get(2).members()).containsExactly("rubygems-hosted", "rubygems-proxy");
        assertThat(handler.settings()).extracting(SettingDescriptor::key)
                .containsExactly("rubygems.signin", "rubygems.maxGemSizeMb");
        assertThat(handler.settings().get(1).appliesTo()).containsExactly(RepositoryType.HOSTED);
    }

    @Test
    void validatesSettings() {
        handler.validate(repo);
        repo.setFormatSettings(Map.of("rubygems.signin", "false", "rubygems.maxGemSizeMb", "10"));
        handler.validate(repo);
        repo.setFormatSettings(Map.of("rubygems.signin", "maybe"));
        assertThatThrownBy(() -> handler.validate(repo)).isInstanceOf(GranaryException.class).hasMessageContaining("true or false");
        for (String bad : List.of("0", "-1", "lots", "1.5")) {
            repo.setFormatSettings(Map.of("rubygems.maxGemSizeMb", bad));
            assertThatThrownBy(() -> handler.validate(repo)).as(bad).isInstanceOf(GranaryException.class);
        }
    }

    @Test
    void requiresReadForReadsWriteForWritesAndNothingForSignin() {
        assertThat(handler.requiredAccess(request(repo, "GET", "versions"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(repo, "HEAD", "gems/a-1.0.gem"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(repo, "POST", "api/v1/gems"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(repo, "DELETE", "api/v1/gems/yank"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(repo, "PUT", "api/v1/gems/unyank"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(repo, "POST", "api/v1/api_key"))).isEqualTo(AccessLevel.NONE);
        assertThat(handler.requiredAccess(request(repo, "GET", "api/v1/api_key.json"))).isEqualTo(AccessLevel.NONE);
    }

    @Test
    void separatesMutableIndexesFromImmutableFiles() {
        for (String metadata : List.of("versions", "names", "info/rake", "specs.4.8.gz", "latest_specs.4.8.gz",
                "prerelease_specs.4.8.gz", "api/v1/gems/rake.json", "api/v1/versions/rake.json",
                "api/v1/versions/rake/latest.json")) {
            assertThat(handler.isMetadataPath(metadata)).as(metadata).isTrue();
        }
        for (String file : List.of("gems/rake-13.4.2.gem", "quick/Marshal.4.8/rake-13.4.2.gemspec.rz", "", "api/v1/gems",
                "info/../x", "unknown")) {
            assertThat(handler.isMetadataPath(file)).as(file).isFalse();
        }
    }

    // --- push and read --------------------------------------------------------------------------------

    @Test
    void pushStoresTheGemItsQuickSpecAndItsRecord() throws IOException {
        byte[] gem = resource("acme_hello-0.1.0.gem");
        ResponseEntity<?> response = push(repo, gem);

        assertThat(status(response)).isEqualTo(HttpStatus.OK);
        assertThat(text(response)).isEqualTo("Successfully registered gem: acme_hello (0.1.0)");
        assertThat(storage.paths(repo)).contains("gems/acme_hello-0.1.0.gem",
                "quick/Marshal.4.8/acme_hello-0.1.0.gemspec.rz", "meta/acme_hello/acme_hello-0.1.0.json");
        assertThat(storage.bytes(repo, "gems/acme_hello-0.1.0.gem")).isEqualTo(gem);
        assertThat(storage.coordinates.values()).allSatisfy(c ->
                assertThat(c).isEqualTo(new StorageService.ComponentCoordinates("", "acme_hello", "0.1.0")));
        assertThat(storage.components).hasSize(1);
        GemRecord record = GemRecord.fromJson(storage.bytes(repo, "meta/acme_hello/acme_hello-0.1.0.json"));
        assertThat(record.sha256()).isEqualTo("a8b38fb64a3678aad435a1e1524cadea1746fa17aa9340db8bd1c5354a2c221f");
        assertThat(record.size()).isEqualTo(gem.length);
        assertThat(record.pushedBy()).isEqualTo("alice");
        assertThat(record.createdAt()).isNotNull();
        assertThat(QuickSpec.read(storage.bytes(repo, "quick/Marshal.4.8/acme_hello-0.1.0.gemspec.rz")).fullName())
                .isEqualTo("acme_hello-0.1.0");
        assertThat(storage.asset(repo, "gems/acme_hello-0.1.0.gem").isMetadata()).isFalse();
        assertThat(storage.asset(repo, "meta/acme_hello/acme_hello-0.1.0.json").isMetadata()).isTrue();
    }

    @Test
    void servesTheCompactIndexOfPushedGems() throws IOException {
        push(repo, resource("acme_hello-0.1.0.gem"));
        push(repo, gem("acme_hello", "0.2.0.beta", "rake:>= 13"));
        push(repo, platformGem("acme_native", "1.0.0", "x86_64-linux"));

        ResponseEntity<?> info = get(repo, "info/acme_hello");
        assertThat(status(info)).isEqualTo(HttpStatus.OK);
        List<String> lines = CompactIndex.lines(text(info));
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).startsWith("0.1.0 rake:>= 12.0|checksum:a8b38fb64a3678aad435a1e1524cadea1746fa17aa9340db8bd1c5354a2c221f,ruby:>= 2.7,created_at:");
        assertThat(lines.get(1)).startsWith("0.2.0.beta rake:>= 13|checksum:");
        assertThat(text(info)).startsWith("---\n");

        ResponseEntity<?> versions = get(repo, "versions");
        String doc = text(versions);
        assertThat(doc).startsWith("created_at: ").contains("\n---\n");
        assertThat(doc).contains("acme_hello 0.1.0,0.2.0.beta " + CompactIndex.md5(text(info)) + "\n");
        assertThat(doc).contains("acme_native 1.0.0-x86_64-linux " + CompactIndex.md5(text(get(repo, "info/acme_native"))) + "\n");
        assertThat(versions.getHeaders().getETag()).isEqualTo("\"" + CompactIndex.md5(doc) + "\"");
        assertThat(versions.getHeaders().getFirst("Repr-Digest")).isEqualTo("sha-256=:"
                + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256(bytes(versions)))) + ":");
        assertThat(versions.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(versions.getHeaders().getContentType().toString()).isEqualTo("text/plain;charset=utf-8");

        assertThat(text(get(repo, "names"))).isEqualTo("---\nacme_hello\nacme_native\n");
        assertThat(status(get(repo, "info/unknown"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void servesTheMarshalIndexesOfPushedGems() throws IOException {
        push(repo, resource("acme_hello-0.1.0.gem"));
        push(repo, gem("acme_hello", "0.2.0.beta"));
        push(repo, gem("acme_hello", "0.0.9"));
        push(repo, platformGem("acme_native", "1.0.0", "x86_64-linux"));

        assertThat(specs(get(repo, "specs.4.8.gz"))).containsExactly(new SpecTuple("acme_hello", "0.0.9", "ruby"),
                new SpecTuple("acme_hello", "0.1.0", "ruby"), new SpecTuple("acme_native", "1.0.0", "x86_64-linux"));
        assertThat(specs(get(repo, "latest_specs.4.8.gz"))).containsExactly(new SpecTuple("acme_hello", "0.1.0", "ruby"),
                new SpecTuple("acme_native", "1.0.0", "x86_64-linux"));
        assertThat(specs(get(repo, "prerelease_specs.4.8.gz"))).containsExactly(new SpecTuple("acme_hello", "0.2.0.beta", "ruby"));

        ResponseEntity<?> quick = get(repo, "quick/Marshal.4.8/acme_native-1.0.0-x86_64-linux.gemspec.rz");
        assertThat(status(quick)).isEqualTo(HttpStatus.OK);
        assertThat(quick.getHeaders().getCacheControl()).contains("max-age=31536000");
        assertThat(QuickSpec.read(bytes(quick)).platform()).isEqualTo("x86_64-linux");
        assertThat(status(get(repo, "quick/Marshal.4.8/nothing-1.0.gemspec.rz"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void servesGemsAndCountsDownloads() throws IOException {
        byte[] gem = resource("acme_hello-0.1.0.gem");
        push(repo, gem);

        ResponseEntity<?> response = get(repo, "gems/acme_hello-0.1.0.gem");
        assertThat(status(response)).isEqualTo(HttpStatus.OK);
        assertThat(bytes(response)).isEqualTo(gem);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"" + sha256(gem) + "\"");
        assertThat(response.getHeaders().getFirst("X-Checksum-Sha256")).isEqualTo(sha256(gem));
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/octet-stream");
        assertThat(storage.downloads).hasSize(1);

        ResponseEntity<?> head = handler.handle(request(repo, "HEAD", "gems/acme_hello-0.1.0.gem"));
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isEqualTo(gem.length);
        ResponseEntity<?> notModified = get(repo, "gems/acme_hello-0.1.0.gem",
                Map.of("If-None-Match", "\"" + sha256(gem) + "\""));
        assertThat(status(notModified)).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(storage.downloads).hasSize(1);

        assertThat(status(get(repo, "gems/acme_hello-9.9.9.gem"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void compactIndexAnswersConditionalAndHeadRequests() throws IOException {
        push(repo, resource("acme_hello-0.1.0.gem"));
        ResponseEntity<?> versions = get(repo, "versions");
        String etag = versions.getHeaders().getETag();

        ResponseEntity<?> notModified = get(repo, "versions", Map.of("If-None-Match", etag));
        assertThat(status(notModified)).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(notModified.getHeaders().getETag()).isEqualTo(etag);
        assertThat(status(get(repo, "versions", Map.of("If-None-Match", "\"other\", " + etag)))).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(status(get(repo, "versions", Map.of("If-None-Match", "W/" + etag)))).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(status(get(repo, "versions", Map.of("If-None-Match", "\"other\"")))).isEqualTo(HttpStatus.OK);

        ResponseEntity<?> head = handler.handle(request(repo, "HEAD", "versions"));
        assertThat(status(head)).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isEqualTo(bytes(versions).length);
        // GET leaves Content-Length to Spring, which answers Range requests from the Resource body
        assertThat(versions.getHeaders().getContentLength()).isEqualTo(-1);
    }

    @Test
    void derivedIndexesAreBuiltOnceAndRebuiltAfterChanges() throws IOException {
        push(repo, resource("acme_hello-0.1.0.gem"));
        String first = text(get(repo, "versions"));
        int stores = storage.stores.size();
        assertThat(text(get(repo, "versions"))).isEqualTo(first);
        assertThat(storage.stores).hasSize(stores);
        assertThat(storage.asset(repo, "versions").getOrigin()).isEqualTo(dev.granary.core.model.Asset.Origin.GENERATED);
        assertThat(storage.asset(repo, "versions").isMetadata()).isTrue();

        push(repo, gem("other", "1.0"));
        String second = text(get(repo, "versions"));
        assertThat(second).contains("other 1.0 ");
        assertThat(storage.stores.size()).isGreaterThan(stores);
    }

    @Test
    void deletingAComponentInTheUiRemovesItFromEveryIndex() throws IOException {
        push(repo, resource("acme_hello-0.1.0.gem"));
        push(repo, gem("acme_hello", "0.2.0"));
        assertThat(text(get(repo, "versions"))).contains("acme_hello 0.1.0,0.2.0 ");

        storage.deleteComponent(repo, "acme_hello", "0.2.0");

        assertThat(text(get(repo, "versions"))).contains("acme_hello 0.1.0 ").doesNotContain("0.2.0");
        assertThat(text(get(repo, "info/acme_hello"))).doesNotContain("0.2.0");
        assertThat(specs(get(repo, "specs.4.8.gz"))).containsExactly(new SpecTuple("acme_hello", "0.1.0", "ruby"));
        assertThat(status(get(repo, "gems/acme_hello-0.2.0.gem"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anEmptyRepositoryHasEmptyIndexes() throws IOException {
        assertThat(text(get(repo, "versions"))).isEqualTo("created_at: 1970-01-01T00:00:00Z\n---\n");
        assertThat(text(get(repo, "names"))).isEqualTo("---\n");
        assertThat(specs(get(repo, "specs.4.8.gz"))).isEmpty();
        assertThat(specs(get(repo, "latest_specs.4.8.gz"))).isEmpty();
    }

    // --- push refusals --------------------------------------------------------------------------------

    @Test
    void refusesToOverwriteAVersion() throws IOException {
        byte[] gem = resource("acme_hello-0.1.0.gem");
        push(repo, gem);
        ResponseEntity<?> again = push(repo, gem);
        assertThat(status(again)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(text(again)).startsWith("Repushing of gem versions is not allowed.")
                .contains("acme_hello (0.1.0) already exists in gems-hosted");
        assertThat(again.getHeaders().getContentType().toString()).isEqualTo("text/plain;charset=utf-8");
    }

    @Test
    void overwritesAVersionWhenRedeployIsAllowed() throws IOException {
        repo.setAllowRedeploy(true);
        push(repo, gem("acme_hello", "1.0", "rake:>= 1"));
        ResponseEntity<?> again = push(repo, gem("acme_hello", "1.0", "rake:>= 2"));
        assertThat(status(again)).isEqualTo(HttpStatus.OK);
        assertThat(text(get(repo, "info/acme_hello"))).contains("1.0 rake:>= 2|").doesNotContain("rake:>= 1");
    }

    @Test
    void refusesNamesThatDifferOnlyInCase() throws IOException {
        push(repo, gem("acme_hello", "1.0"));
        ResponseEntity<?> clash = push(repo, gem("Acme_Hello", "2.0"));
        assertThat(status(clash)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(text(clash)).contains("'acme_hello'");
    }

    @Test
    void refusesWhatIsNotAGem() throws IOException {
        ResponseEntity<?> invalid = push(repo, "this is not a gem at all".repeat(40).getBytes(StandardCharsets.UTF_8));
        assertThat(status(invalid)).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(text(invalid)).startsWith("RubyGems cannot process this gem.\n");

        ResponseEntity<?> empty = push(repo, new byte[0]);
        assertThat(status(empty)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text(empty)).contains("no body");
        assertThat(storage.paths(repo)).isEmpty();
    }

    @Test
    void refusesGemsLargerThanTheConfiguredLimit() throws IOException {
        repo.setFormatSettings(Map.of("rubygems.maxGemSizeMb", "1"));
        ResponseEntity<?> response = push(repo, new byte[1024 * 1024 + 1]);
        assertThat(status(response)).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(text(response)).contains("1 MiB").contains("rubygems.maxGemSizeMb");
        assertThat(java.nio.file.Files.list(derived.tempDir())).isEmpty();
    }

    // --- yank and unyank ------------------------------------------------------------------------------

    @Test
    void yankRemovesAVersionFromTheIndexesAndUnyankRestoresIt() throws IOException {
        push(repo, resource("acme_hello-0.1.0.gem"));
        push(repo, gem("acme_hello", "0.2.0"));

        ResponseEntity<?> yank = form(repo, "DELETE", "api/v1/gems/yank", "gem_name=acme_hello&version=0.2.0");
        assertThat(status(yank)).isEqualTo(HttpStatus.OK);
        assertThat(text(yank)).isEqualTo("Successfully deleted gem: acme_hello (0.2.0)");
        assertThat(text(get(repo, "versions"))).contains("acme_hello 0.1.0 ").doesNotContain("0.2.0");
        assertThat(text(get(repo, "info/acme_hello"))).doesNotContain("0.2.0");
        assertThat(specs(get(repo, "specs.4.8.gz"))).extracting(SpecTuple::version).containsExactly("0.1.0");
        assertThat(status(get(repo, "gems/acme_hello-0.2.0.gem"))).as("the file itself stays").isEqualTo(HttpStatus.OK);
        GemRecord yanked = GemRecord.fromJson(storage.bytes(repo, "meta/acme_hello/acme_hello-0.2.0.json"));
        assertThat(yanked.yanked()).isTrue();
        assertThat(yanked.yankedAt()).isNotNull();

        ResponseEntity<?> twice = form(repo, "DELETE", "api/v1/gems/yank", "gem_name=acme_hello&version=0.2.0");
        assertThat(status(twice)).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(text(twice)).contains("already been yanked");

        ResponseEntity<?> unyank = form(repo, "PUT", "api/v1/gems/unyank", "gem_name=acme_hello&version=0.2.0");
        assertThat(status(unyank)).isEqualTo(HttpStatus.OK);
        assertThat(text(unyank)).isEqualTo("Successfully reinstated gem: acme_hello (0.2.0)");
        assertThat(text(get(repo, "versions"))).contains("acme_hello 0.1.0,0.2.0 ");
        assertThat(status(form(repo, "PUT", "api/v1/gems/unyank", "gem_name=acme_hello&version=0.2.0")))
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void yankingEveryVersionLeavesAnEmptyInfoAndNoVersionsLine() throws IOException {
        push(repo, gem("solo", "1.0"));
        form(repo, "DELETE", "api/v1/gems/yank", "gem_name=solo&version=1.0");
        assertThat(text(get(repo, "versions"))).doesNotContain("solo");
        assertThat(text(get(repo, "names"))).isEqualTo("---\n");
        assertThat(text(get(repo, "info/solo"))).isEqualTo("---\n");
        assertThat(status(get(repo, "api/v1/gems/solo.json"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void yankPicksThePlatformVersion() throws IOException {
        push(repo, gem("multi", "1.0"));
        push(repo, platformGem("multi", "1.0", "java"));
        push(repo, platformGem("onlynative", "2.0", "x86_64-linux"));

        assertThat(text(form(repo, "DELETE", "api/v1/gems/yank", "gem_name=multi&version=1.0&platform=java")))
                .isEqualTo("Successfully deleted gem: multi (1.0-java)");
        assertThat(text(get(repo, "versions"))).contains("multi 1.0 ");
        assertThat(text(form(repo, "DELETE", "api/v1/gems/yank", "gem_name=multi&version=1.0")))
                .isEqualTo("Successfully deleted gem: multi (1.0)");
        assertThat(text(form(repo, "DELETE", "api/v1/gems/yank", "gem_name=onlynative&version=2.0")))
                .as("the only version with that number").isEqualTo("Successfully deleted gem: onlynative (2.0-x86_64-linux)");
    }

    @Test
    void yankReportsWhatIsMissing() throws IOException {
        push(repo, gem("acme_hello", "1.0"));
        ResponseEntity<?> unknownGem = form(repo, "DELETE", "api/v1/gems/yank", "gem_name=nope&version=1.0");
        assertThat(status(unknownGem)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(text(unknownGem)).isEqualTo("This rubygem could not be found.");
        ResponseEntity<?> unknownVersion = form(repo, "DELETE", "api/v1/gems/yank", "gem_name=acme_hello&version=9.9");
        assertThat(status(unknownVersion)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(text(unknownVersion)).isEqualTo("The version 9.9 does not exist for acme_hello.");
        assertThat(status(form(repo, "DELETE", "api/v1/gems/yank", "gem_name=acme_hello&version=1.0&platform=java")))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(form(repo, "DELETE", "api/v1/gems/yank", "version=1.0"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(form(repo, "DELETE", "api/v1/gems/yank", "gem_name=acme_hello"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(form(repo, "DELETE", "api/v1/gems/yank", "gem_name=..%2Fx&version=1"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void yankAcceptsQueryParametersToo() throws IOException {
        push(repo, gem("acme_hello", "1.0"));
        ResponseEntity<?> response = withParameters(repo, "DELETE", "api/v1/gems/yank", user("alice"),
                Map.of("gem_name", "acme_hello", "version", "1.0"));
        assertThat(status(response)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void parsesFormBodies() {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        RubygemsFormatHandler.parseInto("gem_name=a%2Bb&version=1.0+rc&&flag&bad=%zz&gem_name=second", fields);
        assertThat(fields).containsExactly(Map.entry("gem_name", "a+b"), Map.entry("version", "1.0 rc"),
                Map.entry("flag", ""));
    }

    // --- gem signin -----------------------------------------------------------------------------------

    @Test
    void signinIssuesAnApiTokenForTheAuthenticatedUser() throws IOException {
        User alice = new User();
        alice.setUsername("alice");
        when(users.find("alice")).thenReturn(Optional.of(alice));
        when(tokens.issue(eq(alice), any(), isNull())).thenReturn(new ApiTokenService.IssuedToken(new ApiToken(), "gry_secret"));

        ResponseEntity<?> response = withParameters(repo, "POST", "api/v1/api_key", user("alice"),
                Map.of("name", "laptop", "index_rubygems", "true"));
        assertThat(status(response)).isEqualTo(HttpStatus.OK);
        assertThat(text(response)).isEqualTo("gry_secret");
        verify(tokens).issue(alice, "laptop", null);

        assertThat(text(withParameters(repo, "GET", "api/v1/api_key", user("alice"), Map.of()))).isEqualTo("gry_secret");
        verify(tokens).issue(alice, "gem signin", null);
    }

    @Test
    void signinWithoutCredentialsAsksForThem() throws IOException {
        ResponseEntity<?> response = withParameters(repo, "POST", "api/v1/api_key", anonymous(), Map.of());
        assertThat(status(response)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic ");
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    void signinCanBeTurnedOff() throws IOException {
        repo.setFormatSettings(Map.of("rubygems.signin", "false"));
        ResponseEntity<?> response = withParameters(repo, "POST", "api/v1/api_key", user("alice"), Map.of());
        assertThat(status(response)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(text(response)).contains("disabled");
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    void signinScopeUpdatesAndHeadIssueNothing() throws IOException {
        ResponseEntity<?> put = form(repo, "PUT", "api/v1/api_key", "api_key=gry_abc&push_rubygem=true");
        assertThat(status(put)).isEqualTo(HttpStatus.OK);
        assertThat(text(put)).isEqualTo("gry_abc");
        assertThat(status(form(repo, "PUT", "api/v1/api_key", "push_rubygem=true"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(handler.handle(request(repo, "HEAD", "api/v1/api_key")))).isEqualTo(HttpStatus.OK);
        assertThat(status(handler.handle(request(repo, "DELETE", "api/v1/api_key")))).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    void signinForAnUnknownUserIsRefused() throws IOException {
        when(users.find("ghost")).thenReturn(Optional.empty());
        ResponseEntity<?> response = withParameters(repo, "POST", "api/v1/api_key", user("ghost"), Map.of());
        assertThat(status(response)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- JSON API -------------------------------------------------------------------------------------

    @Test
    void servesTheJsonApi() throws IOException {
        push(repo, resource("acme_hello-0.1.0.gem"));
        push(repo, gem("acme_hello", "0.2.0.pre"));
        get(repo, "gems/acme_hello-0.1.0.gem");

        JsonNode gem = JSON.readTree(bytes(get(repo, "api/v1/gems/acme_hello.json")));
        assertThat(gem.path("name").asString()).isEqualTo("acme_hello");
        assertThat(gem.path("version").asString()).isEqualTo("0.1.0");
        assertThat(gem.path("downloads").asLong()).isEqualTo(1);
        assertThat(gem.path("version_downloads").asLong()).isEqualTo(1);
        assertThat(gem.path("sha").asString()).isEqualTo("a8b38fb64a3678aad435a1e1524cadea1746fa17aa9340db8bd1c5354a2c221f");
        assertThat(gem.path("gem_uri").asString()).isEqualTo(baseUrl(repo) + "/gems/acme_hello-0.1.0.gem");
        assertThat(gem.path("project_uri").asString()).isEqualTo(baseUrl(repo) + "/api/v1/gems/acme_hello.json");
        assertThat(gem.path("dependencies").path("runtime").get(0).path("name").asString()).isEqualTo("rake");
        assertThat(gem.path("dependencies").path("runtime").get(0).path("requirements").asString()).isEqualTo(">= 12.0");
        assertThat(gem.path("licenses").get(0).asString()).isEqualTo("MIT");

        JsonNode versions = JSON.readTree(bytes(get(repo, "api/v1/versions/acme_hello.json")));
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).path("number").asString()).isEqualTo("0.2.0.pre");
        assertThat(versions.get(0).path("prerelease").asBoolean()).isTrue();
        assertThat(versions.get(1).path("ruby_version").asString()).isEqualTo(">= 2.7");

        ResponseEntity<?> latest = get(repo, "api/v1/versions/acme_hello/latest.json");
        assertThat(text(latest)).isEqualTo("{\"version\":\"0.1.0\"}");
        assertThat(latest.getHeaders().getContentType().toString()).isEqualTo("application/json;charset=utf-8");
        assertThat(status(get(repo, "api/v1/versions/acme_hello/latest.json", Map.of("If-None-Match",
                latest.getHeaders().getETag())))).isEqualTo(HttpStatus.NOT_MODIFIED);

        for (String missing : List.of("api/v1/gems/nope.json", "api/v1/versions/nope.json", "api/v1/versions/nope/latest.json")) {
            ResponseEntity<?> response = get(repo, missing);
            assertThat(status(response)).as(missing).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(message(response)).isEqualTo("This rubygem could not be found.");
        }
    }

    @Test
    void thePrereleaseIsLatestWhenThereIsNothingElse() throws IOException {
        push(repo, gem("early", "0.1.0.alpha"));
        assertThat(text(get(repo, "api/v1/versions/early/latest.json"))).isEqualTo("{\"version\":\"0.1.0.alpha\"}");
    }

    @Test
    void theRetiredDependencyApiIsNotFound() throws IOException {
        ResponseEntity<?> response = get(repo, "api/v1/dependencies?gems=rake");
        assertThat(status(response)).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<?> plain = get(repo, "api/v1/dependencies");
        assertThat(status(plain)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(text(plain)).contains("compact index");
    }

    // --- everything else ------------------------------------------------------------------------------

    @Test
    void landingPageExplainsHowToUseTheRepository() throws IOException {
        ResponseEntity<?> response = get(repo, "");
        assertThat(status(response)).isEqualTo(HttpStatus.OK);
        assertThat(text(response)).contains("gem sources --add " + baseUrl(repo) + "/")
                .contains("gem push --host " + baseUrl(repo));
        ResponseEntity<?> head = handler.handle(request(repo, "HEAD", ""));
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isEqualTo(bytes(response).length);
        assertThat(status(handler.handle(request(repo, "POST", "")))).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void refusesIllegalPathsAndUnsupportedMethods() throws IOException {
        for (String illegal : List.of("info/../versions", "gems/./a-1.gem", "a\\b", "/versions", "info//x", "info/a\u0000b")) {
            assertThat(status(get(repo, illegal))).as(illegal).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        for (String unknown : List.of("nothing", "gems/x.tgz", "info/123", "info/a b", "api/v2/gems",
                "quick/Marshal.4.8/x.gemspec", "gems/..-1.gem")) {
            assertThat(status(get(repo, unknown))).as(unknown).isEqualTo(HttpStatus.NOT_FOUND);
        }
        for (String path : List.of("versions", "names", "info/rake", "specs.4.8.gz", "quick/Marshal.4.8/a-1.gemspec.rz",
                "api/v1/gems/rake.json", "api/v1/versions/rake.json", "api/v1/versions/rake/latest.json")) {
            assertThat(status(handler.handle(request(repo, "POST", path)))).as(path).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }
        assertThat(status(handler.handle(request(repo, "GET", "api/v1/gems")))).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(status(handler.handle(request(repo, "GET", "api/v1/gems/yank")))).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(status(handler.handle(request(repo, "POST", "api/v1/gems/unyank")))).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        ResponseEntity<?> delete = handler.handle(request(repo, "DELETE", "gems/a-1.0.gem"));
        assertThat(status(delete)).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(text(delete)).contains("gem yank");
        assertThat(status(handler.handle(request(repo, "PUT", "gems/a-1.0.gem")))).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(status(handler.handle(request(repo, "PATCH", "gems/a-1.0.gem")))).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void storedRecordsThatAreUnreadableAreIgnored() throws IOException {
        push(repo, gem("good", "1.0"));
        storage.put(repo, "meta/bad/bad-1.0.json", "{not json");
        storage.put(repo, "meta/good/elsewhere-1.0.json", new String(GemRecord.fromJson(
                storage.bytes(repo, "meta/good/good-1.0.json")).toJson(), StandardCharsets.UTF_8));
        storage.put(repo, "meta/good/readme.txt", "ignored");
        assertThat(text(get(repo, "names"))).isEqualTo("---\ngood\n");
        assertThat(hosted.records(repo)).extracting(GemRecord::fullName).containsExactly("good-1.0");
    }

    @Test
    void recordsAreReadOncePerBlob() throws IOException {
        push(repo, gem("memo", "1.0"));
        assertThat(hosted.records(repo, "memo")).hasSize(1);
        assertThat(hosted.record(repo, "memo", "memo-1.0")).isPresent();
        assertThat(hosted.record(repo, "memo", "memo-2.0")).isEmpty();
        assertThat(hosted.downloads(repo, "memo")).containsEntry("memo-1.0", 0L);
    }

    private static List<SpecTuple> specs(ResponseEntity<?> response) throws IOException {
        assertThat(status(response)).isEqualTo(HttpStatus.OK);
        return SpecsIndex.read(new ByteArrayInputStream(bytes(response)));
    }
}
