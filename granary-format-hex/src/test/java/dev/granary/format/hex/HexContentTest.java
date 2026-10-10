package dev.granary.format.hex;

import static dev.granary.format.hex.HexTestSupport.golden;
import static dev.granary.format.hex.HexTestSupport.goldenKey;
import static dev.granary.format.hex.HexTestSupport.goldenText;
import static dev.granary.format.hex.HexTestSupport.group;
import static dev.granary.format.hex.HexTestSupport.hosted;
import static dev.granary.format.hex.HexTestSupport.proxy;
import static dev.granary.format.hex.HexTestSupport.release;
import static dev.granary.format.hex.HexTestSupport.withSetting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proxies pass hex.pm's signed documents through; groups merge members and re-sign with Granary's key. */
class HexContentTest {

    @TempDir
    Path tempDir;

    private HexFixture fx;
    private HexSigningKey upstreamKey;
    private final Repository hostedRepo = hosted("hex-hosted");
    private final Repository proxyRepo = proxy("hexpm", "https://repo.hex.pm/");
    private final Repository groupRepo = group("hex-group", "hex-hosted", "hexpm");

    @BeforeEach
    void setUp() {
        upstreamKey = goldenKey(tempDir); // plays hex.pm's key
        fx = new HexFixture(new HexSigningKey(tempDir.resolve("granary")));
        fx.members(groupRepo, hostedRepo, proxyRepo);
        fx.upstreamText(proxyRepo, "public_key", goldenText("public_key.pem"));
    }

    private byte[] upstreamDoc(byte[] payload, HexSigningKey signer) {
        return HexRegistry.pack(payload, signer::sign);
    }

    private void upstreamPackage(String name, HexSigningKey signer, String... versions) {
        List<HexRegistry.Release> releases = java.util.Arrays.stream(versions)
                .map(v -> new HexRegistry.Release(v, new byte[32], List.of(), null, ("upstream " + v).getBytes()))
                .toList();
        fx.upstream(proxyRepo, "packages/" + name,
                upstreamDoc(HexRegistry.encodePackage(new HexRegistry.Package(name, "hexpm", releases)), signer));
    }

    private void upstreamIndexes(HexSigningKey signer) {
        fx.upstream(proxyRepo, "names", upstreamDoc(HexRegistry.encodeNames(new HexRegistry.Names("hexpm", List.of(
                new HexRegistry.NamesEntry("jason", new HexRegistry.Timestamp(5, 0)),
                new HexRegistry.NamesEntry("shared", null)))), signer));
        fx.upstream(proxyRepo, "versions", upstreamDoc(HexRegistry.encodeVersions(new HexRegistry.Versions("hexpm", List.of(
                new HexRegistry.VersionsEntry("jason", List.of("1.4.4"), List.of()),
                new HexRegistry.VersionsEntry("shared", List.of("0.1.0", "1.0.0"), List.of(0))))), signer));
    }

    private byte[] groupBytes(HexPath.Kind kind, String name) {
        HexContent.Document doc = fx.content.registryDocument(groupRepo, kind, name).orElseThrow();
        assertThat(doc).isInstanceOf(HexContent.Composed.class);
        return ((HexContent.Composed) doc).bytes();
    }

    private <T> T groupDocument(HexPath.Kind kind, String name, java.util.function.Function<byte[], T> decoder) {
        HexRegistry.Signed signed = HexRegistry.decodeSigned(HexRegistry.gunzip(groupBytes(kind, name)));
        assertThat(fx.key.verify(signed.payload(), signed.signature())).as("re-signed with Granary's key").isTrue();
        return decoder.apply(signed.payload());
    }

    @Test
    void proxiesPassUpstreamDocumentsThroughUntouched() throws IOException {
        byte[] doc = upstreamDoc(HexRegistry.encodeNames(new HexRegistry.Names("hexpm", List.of())), upstreamKey);
        fx.upstream(proxyRepo, "names", doc);
        HexContent.Document result = fx.content.registryDocument(proxyRepo, HexPath.Kind.NAMES, null).orElseThrow();
        assertThat(result).isInstanceOf(HexContent.Stored.class);
        assertThat(((HexContent.Stored) result).content().readAllBytes()).isEqualTo(doc);
        verify(fx.proxy).fetch(eq(proxyRepo), eq("names"), eq(FetchOptions.index()));
        assertThat(fx.content.registryDocument(proxyRepo, HexPath.Kind.PACKAGE, "missing")).isEmpty();
    }

    @Test
    void proxiesServeHexpmGoldenDocumentsByteForByte() throws IOException {
        fx.upstream(proxyRepo, "packages/alpha", golden("package.gz"));
        StoredContent stored = ((HexContent.Stored) fx.content.registryDocument(proxyRepo, HexPath.Kind.PACKAGE, "alpha")
                .orElseThrow()).content();
        assertThat(stored.readAllBytes()).isEqualTo(golden("package.gz"));
    }

    @Test
    void groupsMergeIndexesAndSignThemUnderTheGroupName() {
        fx.publish(hostedRepo, "acme", "1.0.0");
        fx.publish(hostedRepo, "shared", "1.0.0");
        upstreamIndexes(upstreamKey);

        HexRegistry.Names names = groupDocument(HexPath.Kind.NAMES, null, HexRegistry::decodeNames);
        assertThat(names.repository()).isEqualTo("hex-group");
        assertThat(names.names()).containsExactly("acme", "jason", "shared");

        HexRegistry.Versions versions = groupDocument(HexPath.Kind.VERSIONS, null, HexRegistry::decodeVersions);
        assertThat(versions.repository()).isEqualTo("hex-group");
        assertThat(versions.packages()).containsExactly(
                new HexRegistry.VersionsEntry("acme", List.of("1.0.0"), List.of()),
                new HexRegistry.VersionsEntry("jason", List.of("1.4.4"), List.of()),
                new HexRegistry.VersionsEntry("shared", List.of("0.1.0", "1.0.0"), List.of(0))); // hosted 1.0.0 wins
    }

    @Test
    void groupPackagesPreferTheHostedReleaseOfAVersion() throws IOException {
        HexHosted.Published mine = fx.publish(hostedRepo, "shared", "1.0.0");
        upstreamPackage("shared", upstreamKey, "0.1.0", "1.0.0");
        HexRegistry.Package pkg = groupDocument(HexPath.Kind.PACKAGE, "shared", HexRegistry::decodePackage);
        assertThat(pkg.repository()).isEqualTo("hex-group");
        assertThat(pkg.name()).isEqualTo("shared");
        assertThat(pkg.releases()).extracting(HexRegistry.Release::version).containsExactly("0.1.0", "1.0.0");
        assertThat(pkg.release("1.0.0").orElseThrow().outerChecksum()).isEqualTo(mine.tarball().outerChecksum());
        assertThat(pkg.release("0.1.0").orElseThrow().outerChecksum()).isEqualTo("upstream 0.1.0".getBytes());

        StoredContent tarball = fx.content.tarball(groupRepo, "shared", "1.0.0").orElseThrow();
        assertThat(tarball.readAllBytes()).isEqualTo(fx.storage.bytes(hostedRepo, "tarballs/shared-1.0.0.tar"));
    }

    @Test
    void theGroupNameSettingIsWhatClientsSee() {
        withSetting(groupRepo, HexSettings.REPOSITORY_NAME, "acme");
        upstreamPackage("jason", upstreamKey, "1.4.4");
        assertThat(groupDocument(HexPath.Kind.PACKAGE, "jason", HexRegistry::decodePackage).repository()).isEqualTo("acme");
    }

    @Test
    void forgedUpstreamDocumentsAreLeftOut() {
        fx.publish(hostedRepo, "acme", "1.0.0");
        HexSigningKey forger = new HexSigningKey(tempDir.resolve("forger"));
        upstreamIndexes(forger);
        assertThat(groupDocument(HexPath.Kind.NAMES, null, HexRegistry::decodeNames).names()).containsExactly("acme");
        upstreamPackage("jason", forger, "1.4.4");
        assertThat(fx.content.registryDocument(groupRepo, HexPath.Kind.PACKAGE, "jason")).isEmpty();

        withSetting(groupRepo, HexSettings.VERIFY_UPSTREAM, "false");
        assertThat(groupDocument(HexPath.Kind.NAMES, null, HexRegistry::decodeNames).names())
                .containsExactly("acme", "jason", "shared");
    }

    @Test
    void withoutTheUpstreamKeyNothingFromTheProxyIsTrusted() {
        org.mockito.Mockito.when(fx.proxy.fetch(eq(proxyRepo), eq("public_key"), any())).thenReturn(Optional.empty());
        upstreamPackage("jason", upstreamKey, "1.4.4");
        assertThat(fx.content.registryDocument(groupRepo, HexPath.Kind.PACKAGE, "jason")).isEmpty();
        fx.upstreamText(proxyRepo, "public_key", "not a key");
        assertThat(fx.content.registryDocument(groupRepo, HexPath.Kind.PACKAGE, "jason")).isEmpty();
    }

    @Test
    void servingAProxyDocumentCachesTheUpstreamKeyForGroupsToUseOffline() {
        Repository mirror = proxy("hexpm-mirror", "https://mirror.test/");
        byte[] doc = upstreamDoc(HexRegistry.encodePackage(new HexRegistry.Package("jason", "hexpm", List.of())), upstreamKey);
        fx.upstream(mirror, "packages/jason", doc);
        fx.content.registryDocument(mirror, HexPath.Kind.PACKAGE, "jason");
        verify(fx.proxy).fetch(eq(mirror), eq("public_key"), eq(FetchOptions.index()));

        fx.storage.put(mirror, "public_key", goldenText("public_key.pem").getBytes(), "application/x-pem-file", true);
        fx.content.registryDocument(mirror, HexPath.Kind.PACKAGE, "jason");
        verify(fx.proxy, times(1)).fetch(eq(mirror), eq("public_key"), any()); // cached now: never asked again

        fx.content.registryDocument(mirror, HexPath.Kind.PACKAGE, "missing");
        Repository empty = proxy("hexpm-empty", "https://empty.test/");
        fx.content.registryDocument(empty, HexPath.Kind.PACKAGE, "missing");
        verify(fx.proxy, never()).fetch(eq(empty), eq("public_key"), any()); // nothing served, nothing warmed
    }

    @Test
    void undecodableMemberDocumentsAreSkipped() {
        fx.publish(hostedRepo, "acme", "1.0.0");
        fx.upstream(proxyRepo, "names", "not gzip".getBytes());
        assertThat(groupDocument(HexPath.Kind.NAMES, null, HexRegistry::decodeNames).names()).containsExactly("acme");
        fx.upstream(proxyRepo, "packages/jason", HexRegistry.gzip(new byte[] {0x0A, 0x7F}));
        assertThat(fx.content.registryDocument(groupRepo, HexPath.Kind.PACKAGE, "jason")).isEmpty();
    }

    @Test
    void mergedDocumentsAreCachedUntilAMemberChanges() {
        fx.publish(hostedRepo, "acme", "1.0.0");
        upstreamIndexes(upstreamKey);
        byte[] first = groupBytes(HexPath.Kind.NAMES, null);
        assertThat(groupBytes(HexPath.Kind.NAMES, null)).isSameAs(first);
        fx.publish(hostedRepo, "beta", "1.0.0");
        byte[] second = groupBytes(HexPath.Kind.NAMES, null);
        assertThat(second).isNotSameAs(first);
        assertThat(HexRegistry.unpack(second, HexRegistry::decodeNames).names()).contains("beta");
    }

    @Test
    void aPackageNoMemberHasIsMissing() {
        assertThat(fx.content.registryDocument(groupRepo, HexPath.Kind.PACKAGE, "nothing")).isEmpty();
    }

    @Test
    void groupTarballsComeFromTheMemberThatListsThem() throws IOException {
        upstreamPackage("jason", upstreamKey, "1.4.4");
        byte[] upstreamTarball = release("jason", "1.4.4");
        fx.upstream(proxyRepo, "tarballs/jason-1.4.4.tar", upstreamTarball);
        assertThat(fx.content.tarball(groupRepo, "jason", "1.4.4").orElseThrow().readAllBytes()).isEqualTo(upstreamTarball);
        verify(fx.storage.mock, atLeastOnce()).ensureComponent(eq(proxyRepo), any());
        assertThat(fx.content.tarball(groupRepo, "jason", "9.9.9")).isEmpty();
    }

    @Test
    void anUnlistedProxyTarballIsStillAFallback() throws IOException {
        byte[] upstreamTarball = release("orphan", "1.0.0");
        fx.upstream(proxyRepo, "tarballs/orphan-1.0.0.tar", upstreamTarball);
        assertThat(fx.content.tarball(groupRepo, "orphan", "1.0.0").orElseThrow().readAllBytes()).isEqualTo(upstreamTarball);
    }

    @Test
    void docsAndInstallsFallThroughMembers() throws IOException {
        fx.upstream(proxyRepo, "docs/jason-1.4.4.tar.gz", HexTestSupport.docsTarball());
        assertThat(fx.content.docs(groupRepo, "jason", "1.4.4")).isPresent();
        assertThat(fx.content.docs(proxyRepo, "jason", "1.4.4")).isPresent();
        assertThat(fx.content.docs(hostedRepo, "jason", "1.4.4")).isEmpty();

        fx.upstreamText(proxyRepo, "installs/hex-1.x.csv", "2.1.1,abc,1.18.0\n");
        HexPath csv = HexPath.parse("installs/hex-1.x.csv");
        assertThat(fx.content.installs(groupRepo, csv).orElseThrow().readUtf8()).startsWith("2.1.1");
        assertThat(fx.content.installs(hostedRepo, csv)).isEmpty();
        verify(fx.proxy, never()).fetch(eq(hostedRepo), any(), any());
    }

    @Test
    void theProxyPublicKeyIsUpstreams() throws IOException {
        assertThat(fx.content.proxyPublicKey(proxyRepo).orElseThrow().readUtf8()).isEqualTo(goldenText("public_key.pem"));
    }
}
