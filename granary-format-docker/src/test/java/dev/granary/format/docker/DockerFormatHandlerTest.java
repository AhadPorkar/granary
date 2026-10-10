package dev.granary.format.docker;

import static dev.granary.format.docker.DockerTestSupport.MAPPER;
import static dev.granary.format.docker.DockerTestSupport.anonymous;
import static dev.granary.format.docker.DockerTestSupport.anonymousReader;
import static dev.granary.format.docker.DockerTestSupport.baseUrl;
import static dev.granary.format.docker.DockerTestSupport.body;
import static dev.granary.format.docker.DockerTestSupport.bodyBytes;
import static dev.granary.format.docker.DockerTestSupport.config;
import static dev.granary.format.docker.DockerTestSupport.errorCode;
import static dev.granary.format.docker.DockerTestSupport.get;
import static dev.granary.format.docker.DockerTestSupport.group;
import static dev.granary.format.docker.DockerTestSupport.header;
import static dev.granary.format.docker.DockerTestSupport.hosted;
import static dev.granary.format.docker.DockerTestSupport.http;
import static dev.granary.format.docker.DockerTestSupport.index;
import static dev.granary.format.docker.DockerTestSupport.json;
import static dev.granary.format.docker.DockerTestSupport.layer;
import static dev.granary.format.docker.DockerTestSupport.ociManifest;
import static dev.granary.format.docker.DockerTestSupport.proxy;
import static dev.granary.format.docker.DockerTestSupport.referrer;
import static dev.granary.format.docker.DockerTestSupport.request;
import static dev.granary.format.docker.DockerTestSupport.rootRequest;
import static dev.granary.format.docker.DockerTestSupport.user;
import static dev.granary.format.docker.DockerTestSupport.withHeader;
import static dev.granary.format.docker.DockerTestSupport.withQuery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.granary.core.GranaryException;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.RootRequest;
import dev.granary.core.format.RootRoute;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.GranaryPrincipal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class DockerFormatHandlerTest {

    @TempDir
    Path tmp;

    private final FakeStorage storage = new FakeStorage();
    private final DockerUpstream upstream = mock(DockerUpstream.class);
    private final GroupResolver groups = mock(GroupResolver.class);
    private final Repository hostedRepo = hosted("docker-hosted");
    private final Repository hub = proxy("docker-hub", "https://registry-1.docker.io");
    private final Repository groupRepo = group("docker-group", "docker-hosted", "docker-hub");
    private DockerFormatHandler handler;

    private final byte[] cfg = config("app");
    private final byte[] l1 = layer("one");
    private final byte[] manifest = ociManifest(cfg, l1);
    private final Digest manifestDigest = Digest.of(manifest);
    private final Digest l1Digest = Digest.of(l1);

    private DockerFormatHandler handler() {
        if (handler == null) {
            when(groups.leafMembers(groupRepo)).thenReturn(List.of(hostedRepo, hub));
            when(upstream.upstreamName(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
            when(upstream.fetch(any(), anyString(), anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
            DockerRegistry registry = new DockerRegistry(storage.mock, upstream, groups, MAPPER);
            DockerUploads uploads = new DockerUploads(tmp, storage.mock, Clock.systemUTC());
            handler = new DockerFormatHandler(registry, uploads, groups, storage.mock);
        }
        return handler;
    }

    private ResponseEntity<?> call(RepositoryRequest request) {
        try {
            return handler().handle(request);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private ResponseEntity<?> call(Repository repo, String method, String path, byte[] body) {
        return call(request(repo, method, path, body, user("dev")));
    }

    private void pushImage(Repository repo, String name, String tag) {
        for (byte[] blob : List.of(cfg, l1)) {
            storage.put(repo, DockerLayout.blobPath(Digest.of(blob)), blob, MediaTypes.OCTET_STREAM, false);
        }
        ResponseEntity<?> r = call(request(repo, "PUT", "v2/" + name + "/manifests/" + tag, manifest, user("dev")));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ---- identity --------------------------------------------------------------------------------------------

    @Test
    void identityTemplatesAndSettings() {
        DockerFormatHandler h = handler();
        assertThat(h.format()).isEqualTo("docker");
        assertThat(h.displayName()).isEqualTo("Docker / OCI");
        assertThat(h.supportedTypes()).containsExactlyInAnyOrder(RepositoryType.values());

        List<RepositoryTemplate> templates = h.templates();
        assertThat(templates).extracting(RepositoryTemplate::name)
                .containsExactly("docker-hub", "ghcr", "quay", "docker-hosted", "docker-group");
        RepositoryTemplate hubTemplate = templates.get(0);
        assertThat(hubTemplate.type()).isEqualTo(RepositoryType.PROXY);
        assertThat(hubTemplate.remoteUrl()).isEqualTo("https://registry-1.docker.io");
        assertThat(hubTemplate.formatSettings()).containsEntry("docker.libraryPrefix", "true");
        assertThat(templates.get(1).remoteUrl()).isEqualTo("https://ghcr.io");
        assertThat(templates.get(2).remoteUrl()).isEqualTo("https://quay.io");
        assertThat(templates.get(3).type()).isEqualTo(RepositoryType.HOSTED);
        RepositoryTemplate groupTemplate = templates.get(4);
        assertThat(groupTemplate.type()).isEqualTo(RepositoryType.GROUP);
        assertThat(groupTemplate.members()).containsExactly("docker-hosted", "docker-hub", "ghcr", "quay");
        assertThat(groupTemplate.formatSettings()).containsEntry("docker.rootRepository", "true");
        assertThat(templates).allSatisfy(t -> assertThat(t.format()).isEqualTo("docker"));

        List<SettingDescriptor> settings = h.settings();
        assertThat(settings).extracting(SettingDescriptor::key)
                .containsExactly("docker.rootRepository", "docker.libraryPrefix", "docker.challengeAnonymous");
        assertThat(settings).allSatisfy(s -> assertThat(s.type()).isEqualTo(SettingDescriptor.Type.BOOLEAN));
        assertThat(settings.get(0).defaultValue()).isEqualTo("false");
        assertThat(settings.get(1).defaultValue()).isEqualTo("true");
        assertThat(settings.get(1).appliesTo()).containsExactly(RepositoryType.PROXY);
        assertThat(settings.get(2).defaultValue()).isEqualTo("true");
    }

    @Test
    void validateChecksBooleanSettings() {
        DockerFormatHandler h = handler();
        h.validate(hostedRepo);
        hostedRepo.setFormatSettings(Map.of("docker.rootRepository", "yes", "docker.challengeAnonymous", "FALSE"));
        h.validate(hostedRepo);
        hostedRepo.setFormatSettings(Map.of("docker.rootRepository", "maybe"));
        assertThatThrownBy(() -> h.validate(hostedRepo))
                .isInstanceOf(GranaryException.class)
                .satisfies(e -> {
                    assertThat(((GranaryException) e).code()).isEqualTo("repository.docker.setting.invalid");
                    assertThat(((GranaryException) e).status()).isEqualTo(400);
                });
        hub.setFormatSettings(Map.of("docker.libraryPrefix", "nope"));
        assertThatThrownBy(() -> h.validate(hub)).isInstanceOf(GranaryException.class);
    }

    @Test
    void accessAndMetadata() {
        DockerFormatHandler h = handler();
        assertThat(h.requiredAccess(get(hostedRepo, "v2/"))).isEqualTo(AccessLevel.NONE);
        assertThat(h.requiredAccess(get(hostedRepo, "v2"))).isEqualTo(AccessLevel.NONE);
        assertThat(h.requiredAccess(get(hostedRepo, "v2/_catalog"))).isEqualTo(AccessLevel.READ);
        assertThat(h.requiredAccess(get(hostedRepo, "v2/a/manifests/1"))).isEqualTo(AccessLevel.READ);
        assertThat(h.requiredAccess(request(hostedRepo, "HEAD", "v2/a/blobs/x", null, anonymous()))).isEqualTo(AccessLevel.READ);
        assertThat(h.requiredAccess(request(hostedRepo, "PUT", "v2/a/manifests/1", null, anonymous()))).isEqualTo(AccessLevel.WRITE);
        assertThat(h.requiredAccess(request(hostedRepo, "POST", "v2/a/blobs/uploads/", null, anonymous()))).isEqualTo(AccessLevel.WRITE);
        assertThat(h.requiredAccess(request(hostedRepo, "DELETE", "v2/a/manifests/1", null, anonymous()))).isEqualTo(AccessLevel.WRITE);
        assertThat(h.isMetadataPath("tags/a/latest")).isTrue();
        assertThat(h.isMetadataPath("manifests/a/sha256/x")).isFalse();
        assertThat(h.isMetadataPath("blobs/sha256/x")).isFalse();
    }

    // ---- ping ------------------------------------------------------------------------------------------------

    @Test
    void pingChallengesAnonymousCallersByDefault() {
        ResponseEntity<?> anon = call(request(hostedRepo, "GET", "v2/", null, anonymousReader()));
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(header(anon, HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Basic realm=\"Granary\"");
        assertThat(header(anon, DockerResponses.API_VERSION_HEADER)).isEqualTo("registry/2.0");
        assertThat(errorCode(anon)).isEqualTo("UNAUTHORIZED");
        assertThat(json(anon).path("errors").path(0).path("detail").isNull()).isTrue();

        ResponseEntity<?> ok = call(request(hostedRepo, "GET", "v2/", null, user("dev")));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(ok)).isEqualTo("{}");
        assertThat(ok.getHeaders().getContentType().toString()).startsWith("application/json");
        assertThat(header(ok, DockerResponses.API_VERSION_HEADER)).isEqualTo("registry/2.0");
    }

    @Test
    void pingWithoutChallengeFollowsAnonymousRead() {
        hostedRepo.setFormatSettings(Map.of(DockerFormatHandler.CHALLENGE_SETTING, "false"));
        assertThat(call(request(hostedRepo, "GET", "v2/", null, anonymousReader())).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<?> noRead = call(request(hostedRepo, "GET", "v2/", null, anonymous()));
        assertThat(noRead.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(header(noRead, HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Basic realm=\"Granary\"");
        assertThat(call(request(hostedRepo, "POST", "v2/", null, user("dev"))).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    // ---- uploads and manifests -------------------------------------------------------------------------------

    @Test
    void chunkedPushOfAnImage() {
        // start
        ResponseEntity<?> start = call(hostedRepo, "POST", "v2/acme/app/blobs/uploads/", null);
        assertThat(start.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String uuid = header(start, DockerResponses.UPLOAD_UUID_HEADER);
        assertThat(uuid).isNotBlank();
        String location = header(start, HttpHeaders.LOCATION);
        assertThat(location).isEqualTo(baseUrl(hostedRepo) + "/v2/acme/app/blobs/uploads/" + uuid);
        assertThat(header(start, HttpHeaders.RANGE)).isEqualTo("0-0");
        assertThat(start.getHeaders().getContentLength()).isZero();
        assertThat(header(start, DockerResponses.API_VERSION_HEADER)).isEqualTo("registry/2.0");

        // status of an empty session
        ResponseEntity<?> status = call(hostedRepo, "GET", "v2/acme/app/blobs/uploads/" + uuid, null);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(header(status, HttpHeaders.RANGE)).isEqualTo("0-0");
        assertThat(header(status, DockerResponses.UPLOAD_UUID_HEADER)).isEqualTo(uuid);

        // two chunks
        byte[] first = java.util.Arrays.copyOfRange(l1, 0, 4);
        byte[] second = java.util.Arrays.copyOfRange(l1, 4, l1.length);
        ResponseEntity<?> patch1 = call(withHeader(request(hostedRepo, "PATCH", "v2/acme/app/blobs/uploads/" + uuid, first, user("dev")),
                HttpHeaders.CONTENT_RANGE, "0-3"));
        assertThat(patch1.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(header(patch1, HttpHeaders.RANGE)).isEqualTo("0-3");
        assertThat(header(patch1, HttpHeaders.LOCATION)).isEqualTo(location);

        ResponseEntity<?> wrongOffset = call(withHeader(request(hostedRepo, "PATCH", "v2/acme/app/blobs/uploads/" + uuid, second, user("dev")),
                HttpHeaders.CONTENT_RANGE, "bytes 9-20"));
        assertThat(wrongOffset.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(header(wrongOffset, HttpHeaders.RANGE)).isEqualTo("0-3");

        ResponseEntity<?> patch2 = call(withHeader(request(hostedRepo, "PATCH", "v2/acme/app/blobs/uploads/" + uuid, second, user("dev")),
                HttpHeaders.CONTENT_RANGE, "4-" + (l1.length - 1)));
        assertThat(patch2.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(header(patch2, HttpHeaders.RANGE)).isEqualTo("0-" + (l1.length - 1));

        // complete without a digest, then with the right one
        ResponseEntity<?> noDigest = call(hostedRepo, "PUT", "v2/acme/app/blobs/uploads/" + uuid, null);
        assertThat(noDigest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(noDigest)).isEqualTo("DIGEST_INVALID");
        ResponseEntity<?> done = call(withQuery(request(hostedRepo, "PUT", "v2/acme/app/blobs/uploads/" + uuid, null, user("dev")),
                "digest", l1Digest.toString()));
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(header(done, HttpHeaders.LOCATION)).isEqualTo(baseUrl(hostedRepo) + "/v2/acme/app/blobs/" + l1Digest);
        assertThat(header(done, DockerResponses.DIGEST_HEADER)).isEqualTo(l1Digest.toString());
        assertThat(done.getHeaders().getContentLength()).isZero();
        assertThat(storage.bytes(hostedRepo, DockerLayout.blobPath(l1Digest))).isEqualTo(l1);
        assertThat(call(hostedRepo, "GET", "v2/acme/app/blobs/uploads/" + uuid, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // config blob in one PUT with body, then the manifest
        ResponseEntity<?> start2 = call(hostedRepo, "POST", "v2/acme/app/blobs/uploads/", null);
        String uuid2 = header(start2, DockerResponses.UPLOAD_UUID_HEADER);
        ResponseEntity<?> put2 = call(withQuery(request(hostedRepo, "PUT", "v2/acme/app/blobs/uploads/" + uuid2, cfg, user("dev")),
                "digest", Digest.of(cfg).toString()));
        assertThat(put2.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<?> putManifest = call(withHeader(request(hostedRepo, "PUT", "v2/acme/app/manifests/1.0", manifest, user("dev")),
                HttpHeaders.CONTENT_TYPE, MediaTypes.OCI_MANIFEST));
        assertThat(putManifest.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(header(putManifest, HttpHeaders.LOCATION)).isEqualTo(baseUrl(hostedRepo) + "/v2/acme/app/manifests/" + manifestDigest);
        assertThat(header(putManifest, DockerResponses.DIGEST_HEADER)).isEqualTo(manifestDigest.toString());
        assertThat(putManifest.getHeaders().getContentLength()).isZero();
        assertThat(putManifest.getHeaders().containsHeader("OCI-Subject")).isFalse();

        // pull it back
        ResponseEntity<?> getManifest = call(hostedRepo, "GET", "v2/acme/app/manifests/1.0", null);
        assertThat(getManifest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getManifest.getHeaders().getContentType().toString()).isEqualTo(MediaTypes.OCI_MANIFEST);
        assertThat(header(getManifest, DockerResponses.DIGEST_HEADER)).isEqualTo(manifestDigest.toString());
        assertThat(header(getManifest, HttpHeaders.ETAG)).isEqualTo("\"" + manifestDigest + "\"");
        assertThat(getManifest.getHeaders().getContentLength()).isEqualTo(manifest.length);
        assertThat(bodyBytes(getManifest)).isEqualTo(manifest);
        assertThat(storage.downloads).hasSize(1);

        ResponseEntity<?> head = call(hostedRepo, "HEAD", "v2/acme/app/manifests/" + manifestDigest, null);
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isEqualTo(manifest.length);
        assertThat(storage.downloads).hasSize(1);

        ResponseEntity<?> blob = call(hostedRepo, "GET", "v2/acme/app/blobs/" + l1Digest, null);
        assertThat(blob.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(blob.getHeaders().getContentType().toString()).isEqualTo(MediaTypes.OCTET_STREAM);
        assertThat(header(blob, HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(bodyBytes(blob)).isEqualTo(l1);
        assertThat(storage.downloads).hasSize(2);
    }

    @Test
    void monolithicPostAndMount() {
        ResponseEntity<?> mono = call(withQuery(request(hostedRepo, "POST", "v2/acme/app/blobs/uploads/", l1, user("dev")),
                "digest", l1Digest.toString()));
        assertThat(mono.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(header(mono, HttpHeaders.LOCATION)).endsWith("/v2/acme/app/blobs/" + l1Digest);
        assertThat(storage.bytes(hostedRepo, DockerLayout.blobPath(l1Digest))).isEqualTo(l1);

        RepositoryRequest mount = request(hostedRepo, "POST", "v2/acme/other/blobs/uploads/", null, user("dev"));
        withQuery(mount, "mount", l1Digest.toString());
        withQuery(mount, "from", "acme/app");
        ResponseEntity<?> mounted = call(mount);
        assertThat(mounted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(header(mounted, HttpHeaders.LOCATION)).endsWith("/v2/acme/other/blobs/" + l1Digest);
        assertThat(header(mounted, DockerResponses.DIGEST_HEADER)).isEqualTo(l1Digest.toString());

        RepositoryRequest miss = request(hostedRepo, "POST", "v2/acme/other/blobs/uploads/", null, user("dev"));
        withQuery(miss, "mount", Digest.of(cfg).toString());
        withQuery(miss, "from", "acme/app");
        ResponseEntity<?> fallback = call(miss);
        assertThat(fallback.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(header(fallback, DockerResponses.UPLOAD_UUID_HEADER)).isNotBlank();

        ResponseEntity<?> wrong = call(withQuery(request(hostedRepo, "POST", "v2/acme/app/blobs/uploads/", l1, user("dev")),
                "digest", Digest.of(cfg).toString()));
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(wrong)).isEqualTo("DIGEST_INVALID");
        assertThat(json(wrong).path("errors").path(0).path("detail").path("expected").stringValue()).isEqualTo(Digest.of(cfg).toString());
        ResponseEntity<?> badDigest = call(withQuery(request(hostedRepo, "POST", "v2/acme/app/blobs/uploads/", l1, user("dev")),
                "digest", "md5:abc"));
        assertThat(badDigest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(badDigest)).isEqualTo("DIGEST_INVALID");
    }

    @Test
    void uploadSessionErrors() {
        ResponseEntity<?> unknown = call(hostedRepo, "PATCH", "v2/acme/app/blobs/uploads/nope", l1);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(unknown)).isEqualTo("BLOB_UPLOAD_UNKNOWN");

        ResponseEntity<?> start = call(hostedRepo, "POST", "v2/acme/app/blobs/uploads/", null);
        String uuid = header(start, DockerResponses.UPLOAD_UUID_HEADER);
        ResponseEntity<?> badRange = call(withHeader(request(hostedRepo, "PATCH", "v2/acme/app/blobs/uploads/" + uuid, l1, user("dev")),
                HttpHeaders.CONTENT_RANGE, "garbage"));
        assertThat(badRange.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(badRange)).isEqualTo("BLOB_UPLOAD_INVALID");
        assertThat(call(hostedRepo, "POST", "v2/acme/app/blobs/uploads/" + uuid, null).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(call(hostedRepo, "GET", "v2/acme/app/blobs/uploads/", null).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        ResponseEntity<?> cancel = call(hostedRepo, "DELETE", "v2/acme/app/blobs/uploads/" + uuid, null);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(hostedRepo, "GET", "v2/acme/app/blobs/uploads/" + uuid, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(DockerFormatHandler.contentRangeStart("bytes=5-9")).isEqualTo(5);
        assertThat(DockerFormatHandler.contentRangeStart(" 12-20/100")).isEqualTo(12);
        assertThat(DockerFormatHandler.rangeOf(0)).isEqualTo("0-0");
        assertThat(DockerFormatHandler.rangeOf(10)).isEqualTo("0-9");
    }

    @Test
    void manifestErrors() {
        ResponseEntity<?> unknown = call(hostedRepo, "GET", "v2/acme/app/manifests/1.0", null);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(unknown)).isEqualTo("MANIFEST_UNKNOWN");
        assertThat(json(unknown).path("errors").path(0).path("detail").path("name").stringValue()).isEqualTo("acme/app");

        ResponseEntity<?> badName = call(hostedRepo, "GET", "v2/Acme/app/manifests/1.0", null);
        assertThat(badName.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(badName)).isEqualTo("NAME_INVALID");

        ResponseEntity<?> badTag = call(hostedRepo, "GET", "v2/acme/app/manifests/-bad", null);
        assertThat(badTag.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(badTag)).isEqualTo("TAG_INVALID");
        ResponseEntity<?> badDigest = call(hostedRepo, "GET", "v2/acme/app/manifests/sha256:zz", null);
        assertThat(errorCode(badDigest)).isEqualTo("DIGEST_INVALID");

        ResponseEntity<?> missingBlobs = call(hostedRepo, "PUT", "v2/acme/app/manifests/1.0", manifest);
        assertThat(missingBlobs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(missingBlobs)).isEqualTo("MANIFEST_BLOB_UNKNOWN");

        ResponseEntity<?> invalid = call(hostedRepo, "PUT", "v2/acme/app/manifests/1.0", "nope".getBytes(StandardCharsets.UTF_8));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(invalid)).isEqualTo("MANIFEST_INVALID");

        byte[] huge = new byte[Manifest.MAX_SIZE + 10];
        ResponseEntity<?> tooBig = call(hostedRepo, "PUT", "v2/acme/app/manifests/1.0", huge);
        assertThat(tooBig.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(tooBig)).isEqualTo("MANIFEST_INVALID");

        assertThat(call(hostedRepo, "PATCH", "v2/acme/app/manifests/1.0", manifest).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        ResponseEntity<?> deleteUnknown = call(hostedRepo, "DELETE", "v2/acme/app/manifests/1.0", null);
        assertThat(deleteUnknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(deleteUnknown)).isEqualTo("MANIFEST_UNKNOWN");
    }

    @Test
    void manifestDeleteAndRedeploy() {
        pushImage(hostedRepo, "acme/app", "1.0");
        ResponseEntity<?> again = call(hostedRepo, "PUT", "v2/acme/app/manifests/1.0", manifest);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        byte[] other = ociManifest(cfg);
        ResponseEntity<?> moved = call(hostedRepo, "PUT", "v2/acme/app/manifests/1.0", other);
        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(moved)).isEqualTo("DENIED");
        hostedRepo.setAllowRedeploy(true);
        assertThat(call(hostedRepo, "PUT", "v2/acme/app/manifests/1.0", other).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<?> deleted = call(hostedRepo, "DELETE", "v2/acme/app/manifests/" + Digest.of(other), null);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(call(hostedRepo, "GET", "v2/acme/app/manifests/1.0", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(hostedRepo, "GET", "v2/acme/app/manifests/" + manifestDigest, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void blobErrorsAndDelete() {
        ResponseEntity<?> unknown = call(hostedRepo, "GET", "v2/acme/app/blobs/" + l1Digest, null);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(unknown)).isEqualTo("BLOB_UNKNOWN");
        ResponseEntity<?> bad = call(hostedRepo, "GET", "v2/acme/app/blobs/latest", null);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(bad)).isEqualTo("DIGEST_INVALID");
        assertThat(call(hostedRepo, "PUT", "v2/acme/app/blobs/" + l1Digest, l1).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        storage.put(hostedRepo, DockerLayout.blobPath(l1Digest), l1, MediaTypes.OCTET_STREAM, false);
        ResponseEntity<?> partial = call(withHeader(get(hostedRepo, "v2/acme/app/blobs/" + l1Digest), HttpHeaders.RANGE, "bytes=0-2"));
        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(bodyBytes(partial)).isEqualTo(java.util.Arrays.copyOfRange(l1, 0, 3));
        assertThat(call(hostedRepo, "DELETE", "v2/acme/app/blobs/" + l1Digest, null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(call(hostedRepo, "DELETE", "v2/acme/app/blobs/" + l1Digest, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ociSubjectHeaderAndReferrers() {
        pushImage(hostedRepo, "acme/app", "1.0");
        byte[] sbom = referrer(manifest, "application/vnd.example.sbom", cfg, l1);
        ResponseEntity<?> put = call(hostedRepo, "PUT", "v2/acme/app/manifests/" + Digest.of(sbom), sbom);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(header(put, "OCI-Subject")).isEqualTo(manifestDigest.toString());

        ResponseEntity<?> referrers = call(hostedRepo, "GET", "v2/acme/app/referrers/" + manifestDigest, null);
        assertThat(referrers.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(referrers.getHeaders().getContentType().toString()).isEqualTo(MediaTypes.OCI_INDEX);
        assertThat(referrers.getHeaders().containsHeader("OCI-Filters-Applied")).isFalse();
        assertThat(json(referrers).path("manifests").size()).isEqualTo(1);

        ResponseEntity<?> filtered = call(withQuery(get(hostedRepo, "v2/acme/app/referrers/" + manifestDigest), "artifactType", "x/y"));
        assertThat(header(filtered, "OCI-Filters-Applied")).isEqualTo("artifactType");
        assertThat(json(filtered).path("manifests").size()).isZero();
        assertThat(errorCode(call(hostedRepo, "GET", "v2/acme/app/referrers/latest", null))).isEqualTo("DIGEST_INVALID");
        assertThat(call(hostedRepo, "DELETE", "v2/acme/app/referrers/" + manifestDigest, null).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    // ---- listings --------------------------------------------------------------------------------------------

    @Test
    void tagsAndCatalogWithPagination() {
        pushImage(hostedRepo, "acme/app", "1.0");
        for (String tag : List.of("1.1", "2.0", "latest")) {
            assertThat(call(hostedRepo, "PUT", "v2/acme/app/manifests/" + tag, manifest).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        assertThat(call(hostedRepo, "PUT", "v2/zeta/manifests/1", manifest).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<?> all = call(hostedRepo, "GET", "v2/acme/app/tags/list", null);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode tags = json(all);
        assertThat(tags.path("name").stringValue()).isEqualTo("acme/app");
        assertThat(tags.path("tags")).extracting(JsonNode::stringValue).containsExactly("1.0", "1.1", "2.0", "latest");
        assertThat(all.getHeaders().containsHeader(HttpHeaders.LINK)).isFalse();

        ResponseEntity<?> page1 = call(withQuery(get(hostedRepo, "v2/acme/app/tags/list"), "n", "2"));
        assertThat(json(page1).path("tags")).extracting(JsonNode::stringValue).containsExactly("1.0", "1.1");
        assertThat(header(page1, HttpHeaders.LINK)).isEqualTo("<" + baseUrl(hostedRepo) + "/v2/acme/app/tags/list?n=2&last=1.1>; rel=\"next\"");
        RepositoryRequest p2 = withQuery(get(hostedRepo, "v2/acme/app/tags/list"), "n", "2");
        withQuery(p2, "last", "1.1");
        ResponseEntity<?> page2 = call(p2);
        assertThat(json(page2).path("tags")).extracting(JsonNode::stringValue).containsExactly("2.0", "latest");
        assertThat(page2.getHeaders().containsHeader(HttpHeaders.LINK)).isFalse();

        ResponseEntity<?> unknown = call(hostedRepo, "GET", "v2/nobody/tags/list", null);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(unknown)).isEqualTo("NAME_UNKNOWN");
        assertThat(errorCode(call(withQuery(get(hostedRepo, "v2/acme/app/tags/list"), "n", "x")))).isEqualTo("UNSUPPORTED");
        assertThat(call(hostedRepo, "DELETE", "v2/acme/app/tags/list", null).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        ResponseEntity<?> catalog = call(hostedRepo, "GET", "v2/_catalog", null);
        assertThat(json(catalog).path("repositories")).extracting(JsonNode::stringValue).containsExactly("acme/app", "zeta");
        ResponseEntity<?> catalogPage = call(withQuery(get(hostedRepo, "v2/_catalog"), "n", "1"));
        assertThat(json(catalogPage).path("repositories")).extracting(JsonNode::stringValue).containsExactly("acme/app");
        assertThat(header(catalogPage, HttpHeaders.LINK)).isEqualTo("<" + baseUrl(hostedRepo) + "/v2/_catalog?n=1&last=acme%2Fapp>; rel=\"next\"");
        assertThat(call(hostedRepo, "POST", "v2/_catalog", null).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        ResponseEntity<?> headCatalog = call(hostedRepo, "HEAD", "v2/_catalog", null);
        assertThat(headCatalog.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(headCatalog.getBody()).isNull();
    }

    @Test
    void pageHelper() {
        TreeSet<String> all = new TreeSet<>(List.of("a", "b", "c"));
        assertThat(DockerFormatHandler.page(all, null, null).items()).containsExactly("a", "b", "c");
        assertThat(DockerFormatHandler.page(all, "0", null).items()).isEmpty();
        assertThat(DockerFormatHandler.page(all, "0", null).next()).isNull();
        DockerFormatHandler.Page p = DockerFormatHandler.page(all, "2", null);
        assertThat(p.items()).containsExactly("a", "b");
        assertThat(p.next()).isEqualTo("b");
        assertThat(DockerFormatHandler.page(all, "2", "b").items()).containsExactly("c");
        assertThat(DockerFormatHandler.page(all, "5", "zzz").items()).isEmpty();
        assertThatThrownBy(() -> DockerFormatHandler.page(all, "-1", null)).isInstanceOf(DockerError.class);
    }

    // ---- unknown paths and read-only repositories ------------------------------------------------------------

    @Test
    void unknownEndpoints() {
        ResponseEntity<?> r = call(hostedRepo, "GET", "v2/acme/app", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(r)).isEqualTo("UNSUPPORTED");
        assertThat(call(hostedRepo, "GET", "v1/", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(hostedRepo, "GET", "", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void groupsAndProxiesRefuseWrites() {
        ResponseEntity<?> group = call(groupRepo, "POST", "v2/acme/app/blobs/uploads/", null);
        assertThat(group.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(errorCode(group)).isEqualTo("UNSUPPORTED");
        assertThat(json(group).path("errors").path(0).path("message").stringValue()).contains("docker-hosted");
        assertThat(call(groupRepo, "PUT", "v2/acme/app/manifests/1", manifest).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(call(groupRepo, "DELETE", "v2/acme/app/manifests/1", null).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(call(groupRepo, "DELETE", "v2/acme/app/blobs/" + l1Digest, null).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(call(groupRepo, "PATCH", "v2/acme/app/blobs/uploads/x", l1).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        ResponseEntity<?> proxyWrite = call(hub, "PUT", "v2/acme/app/manifests/1", manifest);
        assertThat(proxyWrite.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(json(proxyWrite).path("errors").path(0).path("message").stringValue()).contains("registry-1.docker.io");
    }

    @Test
    void groupReadsThroughToMembers() {
        pushImage(hostedRepo, "acme/app", "1.0");
        ResponseEntity<?> viaGroup = call(groupRepo, "GET", "v2/acme/app/manifests/1.0", null);
        assertThat(viaGroup.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyBytes(viaGroup)).isEqualTo(manifest);
        assertThat(call(groupRepo, "GET", "v2/acme/app/blobs/" + l1Digest, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(call(groupRepo, "GET", "v2/_catalog", null)).path("repositories").path(0).stringValue()).isEqualTo("acme/app");
        assertThat(call(groupRepo, "GET", "v2/nobody/manifests/1.0", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- root routing ----------------------------------------------------------------------------------------

    private RootRequest root(String method, String path, Repository... repos) {
        List<Repository> all = List.of(repos);
        return new RootRequest(method, path, new RootRequest.RepositoryLookup() {
            @Override
            public Optional<Repository> find(String name) {
                return all.stream().filter(r -> r.getName().equals(name)).findFirst();
            }

            @Override
            public List<Repository> ofFormat(String format) {
                return all.stream().filter(r -> r.getFormat().equals(format)).toList();
            }
        });
    }

    @Test
    void routesRootRequests() {
        DockerFormatHandler h = handler();
        Repository npm = DockerTestSupport.repository("npm-proxy", RepositoryType.PROXY);
        npm.setFormat("npm");

        assertThat(h.routeRoot(root("GET", "repository/x", hostedRepo))).isEmpty();
        assertThat(h.routeRoot(root("GET", "v2ish", hostedRepo))).isEmpty();
        assertThat(h.routeRoot(root("GET", "v2/", npm))).isEmpty();

        // group is the implicit root
        assertThat(h.routeRoot(root("GET", "v2/", hostedRepo, hub, groupRepo, npm)))
                .contains(new RootRoute("docker-group", "v2/"));
        assertThat(h.routeRoot(root("GET", "v2", hostedRepo, groupRepo))).contains(new RootRoute("docker-group", "v2"));
        assertThat(h.routeRoot(root("GET", "v2/_catalog", hostedRepo, groupRepo))).contains(new RootRoute("docker-group", "v2/_catalog"));
        assertThat(h.routeRoot(root("GET", "v2/library/alpine/manifests/3.20", hostedRepo, hub, groupRepo)))
                .contains(new RootRoute("docker-group", "v2/library/alpine/manifests/3.20"));
        // a repository name as first segment wins
        assertThat(h.routeRoot(root("PUT", "v2/docker-hosted/acme/app/manifests/1", hostedRepo, hub, groupRepo)))
                .contains(new RootRoute("docker-hosted", "v2/acme/app/manifests/1"));
        assertThat(h.routeRoot(root("GET", "v2/npm-proxy/x/manifests/1", hostedRepo, groupRepo, npm)))
                .contains(new RootRoute("docker-group", "v2/npm-proxy/x/manifests/1"));
        // a bare repository name without a path still goes to the root
        assertThat(h.routeRoot(root("GET", "v2/docker-hosted", hostedRepo, groupRepo)))
                .contains(new RootRoute("docker-group", "v2/docker-hosted"));

        // without a group the first docker repository by name is the root; a flagged one wins over everything
        assertThat(h.routeRoot(root("GET", "v2/", hub, hostedRepo))).contains(new RootRoute("docker-hosted", "v2/"));
        hub.setFormatSettings(Map.of(DockerFormatHandler.ROOT_SETTING, "true"));
        assertThat(h.routeRoot(root("GET", "v2/", hostedRepo, hub, groupRepo))).contains(new RootRoute("docker-hub", "v2/"));
        assertThat(DockerFormatHandler.rootRepository(List.of())).isEmpty();
    }

    @Test
    void urlsFollowTheWayTheClientCame() {
        storage.put(hostedRepo, DockerLayout.blobPath(l1Digest), l1, MediaTypes.OCTET_STREAM, false);
        storage.put(hostedRepo, DockerLayout.blobPath(Digest.of(cfg)), cfg, MediaTypes.OCTET_STREAM, false);

        // through the root with the repository prefix: host/docker-hosted/acme/app
        ResponseEntity<?> prefixed = call(rootRequest(hostedRepo, "POST", "v2/acme/app/blobs/uploads/",
                "v2/docker-hosted/acme/app/blobs/uploads/", null));
        assertThat(header(prefixed, HttpHeaders.LOCATION)).startsWith(DockerTestSupport.SERVER + "/v2/docker-hosted/acme/app/blobs/uploads/");

        // through the root as the root repository: host/acme/app
        ResponseEntity<?> plain = call(rootRequest(hostedRepo, "PUT", "v2/acme/app/manifests/1.0",
                "v2/acme/app/manifests/1.0", manifest));
        assertThat(plain.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(header(plain, HttpHeaders.LOCATION)).isEqualTo(DockerTestSupport.SERVER + "/v2/acme/app/manifests/" + manifestDigest);

        ResponseEntity<?> tags = call(rootRequest(hostedRepo, "GET", "v2/acme/app/tags/list", "v2/docker-hosted/acme/app/tags/list", null));
        assertThat(json(tags).path("name").stringValue()).isEqualTo("docker-hosted/acme/app");
        ResponseEntity<?> tagsPlain = call(rootRequest(hostedRepo, "GET", "v2/acme/app/tags/list", "v2/acme/app/tags/list", null));
        assertThat(json(tagsPlain).path("name").stringValue()).isEqualTo("acme/app");

        // direct access keeps the repository URL
        ResponseEntity<?> direct = call(withQuery(get(hostedRepo, "v2/acme/app/tags/list"), "n", "0"));
        assertThat(direct.getHeaders().containsHeader(HttpHeaders.LINK)).isFalse();
        assertThat(DockerFormatHandler.v2Base(get(hostedRepo, "v2/x/tags/list"))).isEqualTo(baseUrl(hostedRepo) + "/v2/");
        assertThat(DockerFormatHandler.clientName(get(hostedRepo, "v2/x/tags/list"), "x")).isEqualTo("x");
    }

    // ---- snippets --------------------------------------------------------------------------------------------

    private ConnectContext context(Repository repo, String serverUrl) {
        return new ConnectContext(repo, serverUrl + "/repository/" + repo.getName(), serverUrl, "alice", "gry_token", false);
    }

    @Test
    void snippetsForAHostedRepositoryOverPlainHttp() {
        List<ConnectSnippet> snippets = handler().connectSnippets(context(hostedRepo, "http://granary.test:8081"));
        assertThat(snippets).extracting(ConnectSnippet::id).containsExactly(
                "docker-login", "docker-pull", "docker-push", "docker-daemon", "podman-registries", "podman", "skopeo",
                "buildah", "nerdctl", "containerd-hosts", "oras", "helm", "kubernetes-secret", "kubernetes-pod", "curl");
        Map<String, ConnectSnippet> byId = new java.util.HashMap<>();
        snippets.forEach(s -> byId.put(s.id(), s));
        assertThat(byId.get("docker-login").code()).contains("echo 'gry_token' | docker login granary.test:8081 -u alice --password-stdin");
        assertThat(byId.get("docker-pull").code()).contains("docker pull granary.test:8081/docker-hosted/acme/app:1.0");
        assertThat(byId.get("docker-push").code()).contains("docker push granary.test:8081/docker-hosted/acme/app:1.0");
        assertThat(byId.get("docker-daemon").code()).contains("\"insecure-registries\": [\"granary.test:8081\"]");
        assertThat(byId.get("docker-daemon").filename()).isEqualTo("daemon.json");
        assertThat(byId.get("podman-registries").code()).contains("location = \"granary.test:8081\"").contains("insecure = true");
        assertThat(byId.get("podman-registries").language()).isEqualTo("toml");
        assertThat(byId.get("podman").code()).contains("podman pull --tls-verify=false granary.test:8081/docker-hosted/acme/app:1.0");
        assertThat(byId.get("skopeo").code()).contains("--dest-tls-verify=false");
        assertThat(byId.get("buildah").code()).contains("buildah push --tls-verify=false");
        assertThat(byId.get("nerdctl").code()).contains("--insecure-registry");
        assertThat(byId.get("containerd-hosts").filename()).isEqualTo("/etc/containerd/certs.d/granary.test:8081/hosts.toml");
        assertThat(byId.get("containerd-hosts").code()).contains("server = \"http://granary.test:8081\"").contains("\"push\"").contains("skip_verify = true");
        assertThat(byId.get("oras").code()).contains("oras push --plain-http granary.test:8081/docker-hosted/acme/artifacts:1.0");
        assertThat(byId.get("helm").code()).contains("helm push mychart-0.1.0.tgz oci://granary.test:8081/docker-hosted/charts --plain-http");
        assertThat(byId.get("kubernetes-secret").code()).contains("--docker-server=granary.test:8081").contains("--docker-password='gry_token'");
        assertThat(byId.get("kubernetes-pod").code()).contains("imagePullSecrets").contains("image: granary.test:8081/docker-hosted/acme/app:1.0");
        assertThat(byId.get("curl").code()).contains("http://granary.test:8081/repository/docker-hosted/v2/_catalog");
    }

    @Test
    void snippetsForTheRootGroupOverHttps() {
        groupRepo.setFormatSettings(Map.of(DockerFormatHandler.ROOT_SETTING, "true"));
        List<ConnectSnippet> snippets = handler().connectSnippets(context(groupRepo, "https://registry.example.com"));
        assertThat(snippets).extracting(ConnectSnippet::id)
                .doesNotContain("docker-push", "docker-daemon", "buildah", "curl")
                .contains("docker-login", "docker-pull", "podman-registries", "containerd-hosts", "helm", "oras");
        Map<String, ConnectSnippet> byId = new java.util.HashMap<>();
        snippets.forEach(s -> byId.put(s.id(), s));
        assertThat(byId.get("docker-pull").code()).isEqualTo("docker pull registry.example.com/library/alpine:3.20\n");
        assertThat(byId.get("podman-registries").code()).doesNotContain("insecure");
        assertThat(byId.get("podman").code()).doesNotContain("tls-verify");
        assertThat(byId.get("containerd-hosts").code()).contains("server = \"https://registry.example.com\"")
                .doesNotContain("push").doesNotContain("skip_verify");
        assertThat(byId.get("helm").code()).doesNotContain("--plain-http").doesNotContain("push");
        assertThat(byId.get("oras").code()).doesNotContain("--plain-http").doesNotContain("oras push");
        assertThat(byId.get("nerdctl").code()).doesNotContain("--insecure-registry");
        assertThat(snippets).allSatisfy(s -> {
            assertThat(s.title()).isNotBlank();
            assertThat(s.code()).isNotBlank();
        });
    }

    @Test
    void proxySnippetsPullAnImageTheUpstreamHas() {
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        expected.put("https://registry-1.docker.io", "docker-hub/library/alpine:3.20");
        expected.put("https://ghcr.io", "docker-hub/oras-project/oras:v1.2.0");
        expected.put("https://quay.io/", "docker-hub/prometheus/node-exporter:v1.8.2");
        expected.put("https://registry.k8s.io", "docker-hub/pause:3.10");
        expected.put("https://mcr.microsoft.com", "docker-hub/dotnet/runtime:8.0");
        expected.put("https://registry.example.com:5000", "docker-hub/<image>:<tag>");
        expected.forEach((remote, image) -> {
            Repository proxy = DockerTestSupport.proxy("docker-hub", remote);
            Map<String, ConnectSnippet> byId = new java.util.HashMap<>();
            handler().connectSnippets(context(proxy, "http://granary.test:8081")).forEach(s -> byId.put(s.id(), s));
            assertThat(byId.get("docker-pull").code()).as(remote).isEqualTo("docker pull granary.test:8081/" + image + "\n");
            assertThat(byId).as(remote).doesNotContainKeys("docker-push", "buildah");
        });
        Repository mirror = DockerTestSupport.proxy("mirror", "https://mirror.example.com");
        mirror.setFormatSettings(Map.of(DockerUpstream.LIBRARY_PREFIX_SETTING, "true"));
        Map<String, ConnectSnippet> byId = new java.util.HashMap<>();
        handler().connectSnippets(context(mirror, "http://granary.test:8081")).forEach(s -> byId.put(s.id(), s));
        assertThat(byId.get("docker-pull").code()).contains("granary.test:8081/mirror/library/alpine:3.20");
    }

    @Test
    void anonymousPrincipalFlagIsRespected() {
        GranaryPrincipal reader = anonymousReader();
        assertThat(reader.anonymous()).isTrue();
        assertThat(reader.canRead("docker-hosted")).isTrue();
        assertThat(http(get(hostedRepo, "v2/")).getMethod()).isEqualTo("GET");
        assertThat(index(MediaTypes.OCI_INDEX, Map.of("amd64", manifest))).isNotEmpty();
    }
}
