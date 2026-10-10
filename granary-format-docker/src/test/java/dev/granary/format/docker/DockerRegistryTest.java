package dev.granary.format.docker;

import static dev.granary.format.docker.DockerTestSupport.MAPPER;
import static dev.granary.format.docker.DockerTestSupport.config;
import static dev.granary.format.docker.DockerTestSupport.group;
import static dev.granary.format.docker.DockerTestSupport.hosted;
import static dev.granary.format.docker.DockerTestSupport.imageManifest;
import static dev.granary.format.docker.DockerTestSupport.index;
import static dev.granary.format.docker.DockerTestSupport.layer;
import static dev.granary.format.docker.DockerTestSupport.ociManifest;
import static dev.granary.format.docker.DockerTestSupport.proxy;
import static dev.granary.format.docker.DockerTestSupport.referrer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

class DockerRegistryTest {

    private final FakeStorage storage = new FakeStorage();
    private final DockerUpstream upstream = mock(DockerUpstream.class);
    private final GroupResolver groups = mock(GroupResolver.class);
    private final DockerRegistry registry = new DockerRegistry(storage.mock, upstream, groups, MAPPER);

    private final Repository hostedRepo = hosted("docker-hosted");
    private final Repository hub = proxy("docker-hub", "https://registry-1.docker.io");
    private final Repository groupRepo = group("docker-group", "docker-hosted", "docker-hub");

    private final byte[] cfg = config("app");
    private final byte[] l1 = layer("one");
    private final byte[] l2 = layer("two");
    private final byte[] manifest = ociManifest(cfg, l1, l2);
    private final Digest manifestDigest = Digest.of(manifest);

    DockerRegistryTest() {
        when(groups.leafMembers(groupRepo)).thenReturn(List.of(hostedRepo, hub));
        when(upstream.upstreamName(any(), anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(1);
            return name.contains("/") ? name : "library/" + name;
        });
    }

    private void putBlobs(Repository repo, byte[]... blobs) {
        for (byte[] blob : blobs) {
            storage.put(repo, DockerLayout.blobPath(Digest.of(blob)), blob, MediaTypes.OCTET_STREAM, false);
        }
    }

    private static DockerError error(Throwable t) {
        return (DockerError) t;
    }

    // ---- hosted ----------------------------------------------------------------------------------------------

    @Test
    void pushByTagStoresManifestTagPointerAndComponent() throws IOException {
        putBlobs(hostedRepo, cfg, l1, l2);
        DockerRegistry.Pushed pushed = registry.putManifest(hostedRepo, "acme/app", "1.0", manifest, MediaTypes.OCI_MANIFEST);
        assertThat(pushed.digest()).isEqualTo(manifestDigest);
        assertThat(pushed.subject()).isEmpty();

        String manifestPath = DockerLayout.manifestPath("acme/app", manifestDigest);
        assertThat(storage.bytes(hostedRepo, manifestPath)).isEqualTo(manifest);
        Asset stored = storage.asset(hostedRepo, manifestPath);
        assertThat(stored.getContentType()).isEqualTo(MediaTypes.OCI_MANIFEST);
        assertThat(stored.isMetadata()).isFalse();
        assertThat(storage.text(hostedRepo, "tags/acme/app/1.0")).isEqualTo(manifestDigest.toString());
        assertThat(storage.asset(hostedRepo, "tags/acme/app/1.0").isMetadata()).isTrue();
        StorageService.ComponentCoordinates c = storage.coordinates.get(hostedRepo.getId() + "|" + manifestPath);
        assertThat(c.namespace()).isEqualTo("acme");
        assertThat(c.name()).isEqualTo("app");
        assertThat(c.version()).isEqualTo("1.0");
        assertThat(storage.asset(hostedRepo, manifestPath).getComponentId()).isNotNull();

        DockerRegistry.Found byTag = registry.manifest(hostedRepo, "acme/app", "1.0", null).orElseThrow();
        assertThat(byTag.digest()).isEqualTo(manifestDigest);
        assertThat(byTag.contentType()).isEqualTo(MediaTypes.OCI_MANIFEST);
        assertThat(byTag.content().readAllBytes()).isEqualTo(manifest);
        DockerRegistry.Found byDigest = registry.manifest(hostedRepo, "acme/app", manifestDigest.toString(), null).orElseThrow();
        assertThat(byDigest.content().asset().getPath()).isEqualTo(manifestPath);
        assertThat(registry.manifest(hostedRepo, "acme/app", "2.0", null)).isEmpty();
        assertThat(registry.manifest(hostedRepo, "other", "1.0", null)).isEmpty();
    }

    @Test
    void pushByDigestNeedsNoTagAndMustMatch() throws IOException {
        putBlobs(hostedRepo, cfg, l1, l2);
        registry.putManifest(hostedRepo, "acme/app", manifestDigest.toString(), manifest, null);
        assertThat(storage.paths(hostedRepo)).contains(DockerLayout.manifestPath("acme/app", manifestDigest))
                .noneMatch(p -> p.startsWith("tags/"));
        StorageService.ComponentCoordinates c = storage.coordinates.get(hostedRepo.getId() + "|" + DockerLayout.manifestPath("acme/app", manifestDigest));
        assertThat(c.version()).isEqualTo(manifestDigest.toString());

        Digest other = Digest.of(cfg);
        assertThatThrownBy(() -> registry.putManifest(hostedRepo, "acme/app", other.toString(), manifest, null))
                .isInstanceOf(DockerError.class)
                .satisfies(e -> {
                    assertThat(error(e).code()).isEqualTo(OciErrorCode.DIGEST_INVALID);
                    assertThat(error(e).status()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        assertThatThrownBy(() -> registry.putManifest(hostedRepo, "acme/app", "sha512:abc", manifest, null))
                .satisfies(e -> assertThat(error(e).code()).isEqualTo(OciErrorCode.DIGEST_INVALID));
        assertThatThrownBy(() -> registry.putManifest(hostedRepo, "acme/app", "-bad", manifest, null))
                .satisfies(e -> assertThat(error(e).code()).isEqualTo(OciErrorCode.TAG_INVALID));
    }

    @Test
    void mediaTypeComesFromTheBodyOrTheContentType() throws IOException {
        putBlobs(hostedRepo, cfg, l1);
        byte[] docker = imageManifest(MediaTypes.DOCKER_MANIFEST, cfg, l1);
        registry.putManifest(hostedRepo, "a", "docker", docker, MediaTypes.OCI_MANIFEST);
        assertThat(registry.manifest(hostedRepo, "a", "docker", null).orElseThrow().contentType()).isEqualTo(MediaTypes.DOCKER_MANIFEST);

        byte[] untyped = ("{\"schemaVersion\":2,\"config\":{\"digest\":\"" + Digest.of(cfg) + "\",\"size\":" + cfg.length
                + "},\"layers\":[]}").getBytes(StandardCharsets.UTF_8);
        registry.putManifest(hostedRepo, "a", "untyped", untyped, MediaTypes.DOCKER_MANIFEST + "; charset=utf-8");
        assertThat(registry.manifest(hostedRepo, "a", "untyped", null).orElseThrow().contentType()).isEqualTo(MediaTypes.DOCKER_MANIFEST);
    }

    @Test
    void missingBlobsAndChildManifestsAreRefused() {
        putBlobs(hostedRepo, cfg, l1); // l2 missing
        assertThatThrownBy(() -> registry.putManifest(hostedRepo, "acme/app", "1.0", manifest, null))
                .satisfies(e -> {
                    assertThat(error(e).code()).isEqualTo(OciErrorCode.MANIFEST_BLOB_UNKNOWN);
                    assertThat(error(e).status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error(e).detail()).containsEntry("digest", Digest.of(l2).toString());
                });
        assertThat(storage.paths(hostedRepo)).noneMatch(p -> p.startsWith("manifests/"));

        byte[] list = index(MediaTypes.OCI_INDEX, Map.of("amd64", manifest));
        assertThatThrownBy(() -> registry.putManifest(hostedRepo, "acme/app", "multi", list, null))
                .satisfies(e -> assertThat(error(e).code()).isEqualTo(OciErrorCode.MANIFEST_BLOB_UNKNOWN));
    }

    @Test
    void indexReferencingStoredManifestsIsAccepted() throws IOException {
        putBlobs(hostedRepo, cfg, l1, l2);
        registry.putManifest(hostedRepo, "acme/app", manifestDigest.toString(), manifest, null);
        byte[] list = index(MediaTypes.OCI_INDEX, Map.of("amd64", manifest));
        DockerRegistry.Pushed pushed = registry.putManifest(hostedRepo, "acme/app", "multi", list, null);
        DockerRegistry.Found found = registry.manifest(hostedRepo, "acme/app", "multi", null).orElseThrow();
        assertThat(found.digest()).isEqualTo(pushed.digest());
        assertThat(found.contentType()).isEqualTo(MediaTypes.OCI_INDEX);
    }

    @Test
    void movingATagNeedsRedeploy() throws IOException {
        putBlobs(hostedRepo, cfg, l1, l2);
        registry.putManifest(hostedRepo, "acme/app", "latest", manifest, null);
        registry.putManifest(hostedRepo, "acme/app", "latest", manifest, null); // same digest: idempotent
        byte[] second = ociManifest(cfg, l1);
        assertThatThrownBy(() -> registry.putManifest(hostedRepo, "acme/app", "latest", second, null))
                .satisfies(e -> {
                    assertThat(error(e).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error(e).code()).isEqualTo(OciErrorCode.DENIED);
                    assertThat(error(e).getMessage()).contains("redeploy");
                });
        assertThat(storage.text(hostedRepo, "tags/acme/app/latest")).isEqualTo(manifestDigest.toString());

        hostedRepo.setAllowRedeploy(true);
        registry.putManifest(hostedRepo, "acme/app", "latest", second, null);
        assertThat(storage.text(hostedRepo, "tags/acme/app/latest")).isEqualTo(Digest.of(second).toString());
        assertThat(registry.manifest(hostedRepo, "acme/app", manifestDigest.toString(), null)).isPresent(); // old one still addressable
    }

    @Test
    void oversizedAndBrokenManifestsAreInvalid() {
        byte[] huge = new byte[Manifest.MAX_SIZE + 1];
        assertThatThrownBy(() -> registry.putManifest(hostedRepo, "a", "1", huge, null))
                .satisfies(e -> assertThat(error(e).code()).isEqualTo(OciErrorCode.MANIFEST_INVALID));
        assertThatThrownBy(() -> registry.putManifest(hostedRepo, "a", "1", "{".getBytes(StandardCharsets.UTF_8), null))
                .satisfies(e -> assertThat(error(e).code()).isEqualTo(OciErrorCode.MANIFEST_INVALID));
    }

    @Test
    void deleteByDigestRemovesTagsPointingAtIt() throws IOException {
        putBlobs(hostedRepo, cfg, l1, l2);
        registry.putManifest(hostedRepo, "acme/app", "1.0", manifest, null);
        registry.putManifest(hostedRepo, "acme/app", "latest", manifest, null);
        byte[] other = ociManifest(cfg, l1);
        registry.putManifest(hostedRepo, "acme/app", "2.0", other, null);

        assertThat(registry.deleteManifest(hostedRepo, "acme/app", manifestDigest.toString())).isTrue();
        assertThat(storage.paths(hostedRepo)).doesNotContain("tags/acme/app/1.0", "tags/acme/app/latest",
                DockerLayout.manifestPath("acme/app", manifestDigest));
        assertThat(storage.paths(hostedRepo)).contains("tags/acme/app/2.0");
        assertThat(registry.deleteManifest(hostedRepo, "acme/app", manifestDigest.toString())).isFalse();

        assertThat(registry.deleteManifest(hostedRepo, "acme/app", "2.0")).isTrue();
        assertThat(storage.paths(hostedRepo)).contains(DockerLayout.manifestPath("acme/app", Digest.of(other)));
        assertThat(registry.deleteManifest(hostedRepo, "acme/app", "2.0")).isFalse();
    }

    @Test
    void corruptTagPointerIsTreatedAsMissing() throws IOException {
        storage.put(hostedRepo, "tags/a/b", "garbage".getBytes(StandardCharsets.UTF_8), "text/plain", true);
        assertThat(registry.manifest(hostedRepo, "a", "b", null)).isEmpty();
    }

    @Test
    void hostedBlobs() throws IOException {
        putBlobs(hostedRepo, l1);
        Digest d = Digest.of(l1);
        DockerRegistry.Found found = registry.blob(hostedRepo, "any", d).orElseThrow();
        assertThat(found.contentType()).isEqualTo(MediaTypes.OCTET_STREAM);
        assertThat(found.digest()).isEqualTo(d);
        assertThat(registry.blobExists(hostedRepo, d)).isTrue();
        assertThat(registry.blob(hostedRepo, "any", Digest.of(l2))).isEmpty();
        assertThat(registry.deleteBlob(hostedRepo, d)).isTrue();
        assertThat(registry.deleteBlob(hostedRepo, d)).isFalse();
        assertThat(registry.blobExists(hostedRepo, d)).isFalse();
    }

    @Test
    void tagsAndCatalogOfAHostedRepository() throws IOException {
        putBlobs(hostedRepo, cfg, l1, l2);
        registry.putManifest(hostedRepo, "acme/app", "1.0", manifest, null);
        registry.putManifest(hostedRepo, "acme/app", "0.9", manifest, null);
        registry.putManifest(hostedRepo, "acme/app", "latest", manifest, null);
        registry.putManifest(hostedRepo, "acme/app/nested", "x", manifest, null);
        registry.putManifest(hostedRepo, "zeta", manifestDigest.toString(), manifest, null);

        assertThat(registry.tags(hostedRepo, "acme/app")).contains(new java.util.TreeSet<>(List.of("0.9", "1.0", "latest")));
        assertThat(registry.tags(hostedRepo, "zeta")).contains(new java.util.TreeSet<>()); // known, untagged
        assertThat(registry.tags(hostedRepo, "nobody")).isEmpty();
        assertThat(registry.catalog(hostedRepo)).containsExactly("acme/app", "acme/app/nested", "zeta");
    }

    @Test
    void referrersFilterBySubjectAndArtifactType() throws IOException {
        putBlobs(hostedRepo, cfg, l1, l2);
        registry.putManifest(hostedRepo, "acme/app", "1.0", manifest, null);
        byte[] sbom = referrer(manifest, "application/vnd.example.sbom", cfg, l1);
        byte[] sig = referrer(manifest, "application/vnd.example.signature", cfg, l2);
        byte[] unrelated = referrer(ociManifest(cfg), "application/vnd.example.sbom", cfg, l1);
        registry.putManifest(hostedRepo, "acme/app", Digest.of(sbom).toString(), sbom, null);
        registry.putManifest(hostedRepo, "acme/app", Digest.of(sig).toString(), sig, null);
        registry.putManifest(hostedRepo, "acme/app", Digest.of(unrelated).toString(), unrelated, null);
        storage.put(hostedRepo, DockerLayout.manifestPath("acme/app", Digest.of(l1)), "{".getBytes(StandardCharsets.UTF_8), null, false);

        JsonNode all = registry.referrers(hostedRepo, "acme/app", manifestDigest, null);
        assertThat(all.path("mediaType").stringValue()).isEqualTo(MediaTypes.OCI_INDEX);
        assertThat(all.path("schemaVersion").intValue()).isEqualTo(2);
        assertThat(all.path("manifests").size()).isEqualTo(2);
        JsonNode first = all.path("manifests").path(0);
        assertThat(first.path("mediaType").stringValue()).isEqualTo(MediaTypes.OCI_MANIFEST);
        assertThat(first.path("digest").stringValue()).startsWith("sha256:");
        assertThat(first.path("size").longValue()).isPositive();
        assertThat(first.path("annotations").path("org.example.kind").isString()).isTrue();

        JsonNode filtered = registry.referrers(hostedRepo, "acme/app", manifestDigest, "application/vnd.example.signature");
        assertThat(filtered.path("manifests").size()).isEqualTo(1);
        assertThat(filtered.path("manifests").path(0).path("artifactType").stringValue()).isEqualTo("application/vnd.example.signature");
        assertThat(filtered.path("manifests").path(0).path("digest").stringValue()).isEqualTo(Digest.of(sig).toString());

        assertThat(registry.referrers(hostedRepo, "acme/app", Digest.of(l2), null).path("manifests").size()).isZero();
        assertThat(registry.referrers(hostedRepo, "nobody", manifestDigest, null).path("manifests").size()).isZero();
    }

    // ---- proxy -----------------------------------------------------------------------------------------------

    private void stubUpstreamFetch(Repository repo, String upstreamPath, byte[] body, String contentType, boolean metadata) {
        when(upstream.fetch(eq(repo), anyString(), eq(upstreamPath), anyString(), any(), any())).thenAnswer(inv -> {
            String cachePath = inv.getArgument(1);
            Optional<StoredContent> cached = storage.find(repo, cachePath);
            if (cached.isPresent() && !((FetchOptions) inv.getArgument(4)).metadata()) {
                return cached;
            }
            return Optional.of(storage.putStream(repo, cachePath, new ByteArrayInputStream(body), contentType,
                    Asset.Origin.CACHED, metadata, null));
        });
    }

    @Test
    void proxyManifestByTagIsCachedUnderDigestWithComponent() throws IOException {
        stubUpstreamFetch(hub, "library/alpine/manifests/3.20", manifest, MediaTypes.OCI_MANIFEST, true);
        DockerRegistry.Found found = registry.manifest(hub, "alpine", "3.20", MediaTypes.DEFAULT_ACCEPT).orElseThrow();
        assertThat(found.digest()).isEqualTo(manifestDigest);
        assertThat(found.contentType()).isEqualTo(MediaTypes.OCI_MANIFEST);
        assertThat(found.content().asset().getPath()).isEqualTo("tags/library/alpine/3.20");
        assertThat(storage.bytes(hub, DockerLayout.manifestPath("library/alpine", manifestDigest))).isEqualTo(manifest);
        assertThat(storage.asset(hub, DockerLayout.manifestPath("library/alpine", manifestDigest)).getOrigin()).isEqualTo(Asset.Origin.CACHED);
        StorageService.ComponentCoordinates c = storage.coordinates.get(hub.getId() + "|" + DockerLayout.manifestPath("library/alpine", manifestDigest));
        assertThat(c.namespace()).isEqualTo("library");
        assertThat(c.name()).isEqualTo("alpine");
        assertThat(c.version()).isEqualTo("3.20");
        assertThat(found.content().asset().getComponentId()).isNotNull();
        verify(upstream).fetch(eq(hub), eq("tags/library/alpine/3.20"), eq("library/alpine/manifests/3.20"),
                eq("library/alpine"), eq(FetchOptions.index()), eq(MediaTypes.DEFAULT_ACCEPT));

        // the same tag fetched by a client that accepts no index lands in a variant file
        String docker = MediaTypes.DOCKER_MANIFEST;
        registry.manifest(hub, "alpine", "3.20", docker);
        verify(upstream).fetch(eq(hub), eq("tags/library/alpine/3.20" + MediaTypes.variantKey(docker)),
                eq("library/alpine/manifests/3.20"), eq("library/alpine"), eq(FetchOptions.index()), eq(docker));
    }

    @Test
    void proxyManifestByDigestIsImmutableAndVerified() throws IOException {
        stubUpstreamFetch(hub, "library/alpine/manifests/" + manifestDigest, manifest, MediaTypes.OCI_MANIFEST, false);
        DockerRegistry.Found found = registry.manifest(hub, "alpine", manifestDigest.toString(), null).orElseThrow();
        assertThat(found.content().asset().getPath()).isEqualTo(DockerLayout.manifestPath("library/alpine", manifestDigest));
        verify(upstream).fetch(eq(hub), anyString(), eq("library/alpine/manifests/" + manifestDigest), eq("library/alpine"),
                eq(FetchOptions.artifact()), eq(MediaTypes.DEFAULT_ACCEPT));

        Digest wrong = Digest.of(cfg);
        stubUpstreamFetch(hub, "library/alpine/manifests/" + wrong, manifest, MediaTypes.OCI_MANIFEST, false);
        assertThat(registry.manifest(hub, "alpine", wrong.toString(), null)).isEmpty();
        assertThat(storage.find(hub, DockerLayout.manifestPath("library/alpine", wrong))).isEmpty();
    }

    @Test
    void proxyBlobsAreServedFromCacheBeforeAskingUpstream() throws IOException {
        Digest d = Digest.of(l1);
        stubUpstreamFetch(hub, "library/alpine/blobs/" + d, l1, "application/octet-stream", false);
        assertThat(registry.blob(hub, "alpine", d).orElseThrow().content().readAllBytes()).isEqualTo(l1);
        verify(upstream).fetch(eq(hub), eq(DockerLayout.blobPath(d)), eq("library/alpine/blobs/" + d), eq("library/alpine"),
                eq(FetchOptions.artifact()), eq("*/*"));
        assertThat(registry.blob(hub, "whatever/else", d)).isPresent();
        verify(upstream, never()).fetch(eq(hub), anyString(), eq("whatever/else/blobs/" + d), anyString(), any(), any());

        Digest wrong = Digest.of(l2);
        stubUpstreamFetch(hub, "library/alpine/blobs/" + wrong, l1, "application/octet-stream", false);
        assertThat(registry.blob(hub, "alpine", wrong)).isEmpty();
        assertThat(storage.find(hub, DockerLayout.blobPath(wrong))).isEmpty();
    }

    @Test
    void proxyMissesAreEmpty() throws IOException {
        when(upstream.fetch(any(), anyString(), anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
        assertThat(registry.manifest(hub, "alpine", "nope", null)).isEmpty();
        assertThat(registry.blob(hub, "alpine", Digest.of(l1))).isEmpty();
        assertThat(registry.tags(hub, "alpine")).isEmpty();
    }

    @Test
    void proxyTagsMergeCacheAndUpstreamListing() throws IOException {
        stubUpstreamFetch(hub, "library/alpine/manifests/3.20", manifest, MediaTypes.OCI_MANIFEST, true);
        registry.manifest(hub, "alpine", "3.20", null);
        stubUpstreamFetch(hub, "library/alpine/tags/list?n=1000",
                "{\"name\":\"library/alpine\",\"tags\":[\"3.19\",\"latest\",\"bad tag\"]}".getBytes(StandardCharsets.UTF_8),
                MediaTypes.JSON, true);
        assertThat(registry.tags(hub, "alpine")).contains(new java.util.TreeSet<>(List.of("3.19", "3.20", "latest")));
        verify(upstream).fetch(eq(hub), eq("tags/library/alpine/.list"), eq("library/alpine/tags/list?n=1000"),
                eq("library/alpine"), eq(FetchOptions.index()), eq(MediaTypes.JSON));

        // offline with only the cache: the cached tag is still listed
        when(upstream.fetch(eq(hub), eq("tags/library/alpine/.list"), anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
        assertThat(registry.tags(hub, "alpine")).contains(new java.util.TreeSet<>(List.of("3.20")));

        // a broken cached listing is ignored
        stubUpstreamFetch(hub, "library/alpine/tags/list?n=1000", "{".getBytes(StandardCharsets.UTF_8), MediaTypes.JSON, true);
        assertThat(registry.tags(hub, "alpine")).contains(new java.util.TreeSet<>(List.of("3.20")));
        assertThat(registry.catalog(hub)).containsExactly("library/alpine");
    }

    @Test
    void manifestContentTypeFallsBackToTheDocument() throws IOException {
        StoredContent json = storage.put(hub, "x", manifest, "application/json", false);
        assertThat(registry.manifestContentType(json)).isEqualTo(MediaTypes.OCI_MANIFEST);
        StoredContent typed = storage.put(hub, "y", manifest, MediaTypes.DOCKER_MANIFEST_LIST + "; charset=utf-8", false);
        assertThat(registry.manifestContentType(typed)).isEqualTo(MediaTypes.DOCKER_MANIFEST_LIST);
        StoredContent untyped = storage.put(hub, "z", "{}".getBytes(StandardCharsets.UTF_8), "text/plain", false);
        assertThat(registry.manifestContentType(untyped)).isEqualTo("text/plain");
        StoredContent octet = storage.put(hub, "w", "{}".getBytes(StandardCharsets.UTF_8), null, false);
        assertThat(registry.manifestContentType(octet)).isEqualTo(MediaTypes.OCI_MANIFEST);
    }

    // ---- group -----------------------------------------------------------------------------------------------

    @Test
    void groupReadsFirstMemberThatHasIt() throws IOException {
        putBlobs(hostedRepo, cfg, l1, l2);
        registry.putManifest(hostedRepo, "acme/app", "1.0", manifest, null);
        stubUpstreamFetch(hub, "library/alpine/manifests/3.20", manifest, MediaTypes.OCI_MANIFEST, true);
        when(upstream.fetch(eq(hub), anyString(), eq("acme/app/manifests/1.0"), anyString(), any(), any())).thenReturn(Optional.empty());

        assertThat(registry.manifest(groupRepo, "acme/app", "1.0", null).orElseThrow().content().asset().getRepositoryId())
                .isEqualTo(hostedRepo.getId());
        assertThat(registry.manifest(groupRepo, "alpine", "3.20", null).orElseThrow().content().asset().getRepositoryId())
                .isEqualTo(hub.getId());
        assertThat(registry.blob(groupRepo, "acme/app", Digest.of(l1)).orElseThrow().content().asset().getRepositoryId())
                .isEqualTo(hostedRepo.getId());
        when(upstream.fetch(eq(hub), anyString(), eq("acme/app/blobs/" + Digest.of(cfg)), anyString(), any(), any())).thenReturn(Optional.empty());
        registry.deleteBlob(hostedRepo, Digest.of(cfg));
        assertThat(registry.blob(groupRepo, "acme/app", Digest.of(cfg))).isEmpty();
        when(upstream.fetch(eq(hub), anyString(), eq("nobody/manifests/x"), anyString(), any(), any())).thenReturn(Optional.empty());
        assertThat(registry.manifest(groupRepo, "nobody", "x", null)).isEmpty();
    }

    @Test
    void groupListingsAreUnions() throws IOException {
        putBlobs(hostedRepo, cfg, l1, l2);
        registry.putManifest(hostedRepo, "acme/app", "1.0", manifest, null);
        stubUpstreamFetch(hub, "acme/app/tags/list?n=1000",
                "{\"tags\":[\"2.0\"]}".getBytes(StandardCharsets.UTF_8), MediaTypes.JSON, true);
        stubUpstreamFetch(hub, "library/alpine/manifests/3.20", manifest, MediaTypes.OCI_MANIFEST, true);
        registry.manifest(hub, "alpine", "3.20", null);

        assertThat(registry.tags(groupRepo, "acme/app")).contains(new java.util.TreeSet<>(List.of("1.0", "2.0")));
        assertThat(registry.catalog(groupRepo)).containsExactly("acme/app", "library/alpine");
        when(upstream.fetch(eq(hub), anyString(), eq("nobody/tags/list?n=1000"), anyString(), any(), any())).thenReturn(Optional.empty());
        assertThat(registry.tags(groupRepo, "nobody")).isEmpty();

        byte[] sbom = referrer(manifest, "application/vnd.example.sbom", cfg, l1);
        registry.putManifest(hostedRepo, "acme/app", Digest.of(sbom).toString(), sbom, null);
        assertThat(registry.referrers(groupRepo, "acme/app", manifestDigest, null).path("manifests").size()).isEqualTo(1);
    }

    // ---- concurrent writers ----------------------------------------------------------------------------------

    @Test
    void aManifestPushThatLosesTheInsertRaceIsRetriedWithItsChecks() throws IOException {
        putBlobs(hostedRepo, cfg, l1, l2);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
                    if (attempts.getAndIncrement() == 0) {
                        // the same manifest was pushed by another client in the same instant
                        storage.putStream(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                                inv.getArgument(4), inv.getArgument(5), null);
                        throw new org.springframework.dao.DataIntegrityViolationException("uk_asset_path");
                    }
                    return storage.putStream(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                            inv.getArgument(4), inv.getArgument(5), null);
                }).when(storage.mock).store(any(), anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        DockerRegistry.Pushed pushed = registry.putManifest(hostedRepo, "acme/app", "1.0", manifest, MediaTypes.OCI_MANIFEST);
        assertThat(pushed.digest()).isEqualTo(manifestDigest);
        assertThat(storage.text(hostedRepo, "tags/acme/app/1.0")).isEqualTo(manifestDigest.toString());
        assertThat(attempts.get()).isEqualTo(3); // failed manifest insert, then manifest and tag

        // a different manifest racing for the same new tag: the retry sees the winner's tag and refuses to move it
        byte[] other = ociManifest(cfg, l1);
        attempts.set(0);
        org.mockito.Mockito.doAnswer(inv -> {
                    if (attempts.getAndIncrement() == 0) {
                        storage.put(hostedRepo, "tags/acme/app/2.0", manifestDigest.toString().getBytes(StandardCharsets.UTF_8),
                                "text/plain", true);
                        throw new org.springframework.dao.DataIntegrityViolationException("uk_asset_path");
                    }
                    return storage.putStream(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                            inv.getArgument(4), inv.getArgument(5), null);
                }).when(storage.mock).store(any(), anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        assertThatThrownBy(() -> registry.putManifest(hostedRepo, "acme/app", "2.0", other, MediaTypes.OCI_MANIFEST))
                .isInstanceOf(DockerError.class)
                .satisfies(e -> assertThat(error(e).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(storage.text(hostedRepo, "tags/acme/app/2.0")).isEqualTo(manifestDigest.toString());
    }

    @Test
    void aProxyManifestCachedConcurrentlyIsReadBack() throws IOException {
        stubUpstreamFetch(hub, "library/alpine/manifests/3.20", manifest, MediaTypes.OCI_MANIFEST, true);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
                    StoredContent stored = storage.putStream(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                            inv.getArgument(3), inv.getArgument(4), inv.getArgument(5), null);
                    if (attempts.getAndIncrement() == 0) {
                        throw new org.springframework.dao.DataIntegrityViolationException("uk_asset_path");
                    }
                    return stored;
                }).when(storage.mock).store(any(), anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        DockerRegistry.Found found = registry.manifest(hub, "alpine", "3.20", MediaTypes.DEFAULT_ACCEPT).orElseThrow();
        assertThat(found.digest()).isEqualTo(manifestDigest);
        assertThat(storage.bytes(hub, DockerLayout.manifestPath("library/alpine", manifestDigest))).isEqualTo(manifest);
    }
}
