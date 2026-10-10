package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.model.Repository;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CargoIndexesTest {

    private static final String REMOTE = "https://index.example.test/";
    private static final String STATIC = "https://static.crates.io/crates/";

    @TempDir
    Path temp;

    private CargoFixture fx;
    private Repository hosted;
    private Repository proxy;
    private Repository group;

    @BeforeEach
    void setUp() {
        fx = new CargoFixture(temp);
        hosted = CargoTestSupport.hosted("cargo-hosted");
        proxy = CargoTestSupport.proxy("cargo-proxy", REMOTE);
        group = CargoTestSupport.group("cargo-group", "cargo-proxy", "cargo-hosted");
        // configured proxy first: hosted members still take precedence
        fx.group(group, proxy, hosted);
    }

    static String line(String name, String vers, byte[] crate, boolean yanked) {
        return "{\"name\":\"" + name + "\",\"vers\":\"" + vers + "\",\"deps\":[],\"cksum\":\""
                + Checksums.hex("SHA-256", crate) + "\",\"features\":{},\"yanked\":" + yanked + "}";
    }

    private void publish(String name, String version) throws IOException {
        byte[] body = CargoTestSupport.publishBody(name, version);
        try (PublishRequest request = PublishRequest.read(new ByteArrayInputStream(body), temp.resolve("tmp"))) {
            fx.hosted.publish(hosted, request, PublishMetadata.of(request.metadata()), "alice");
        }
    }

    @Test
    void proxyFetchesTheSparseFileBelowTheRemoteRootAndCachesItUnderIndex() {
        byte[] crate = CargoTestSupport.crate("serde", "1.0.0");
        fx.serve(REMOTE + "se/rd/serde", line("serde", "1.0.0", crate, false) + "\n");
        List<IndexEntry.Sourced<Repository>> entries = fx.indexes.entries(proxy, "Serde");
        assertThat(entries).extracting(s -> s.entry().vers()).containsExactly("1.0.0");
        assertThat(entries).extracting(IndexEntry.Sourced::origin).containsExactly(proxy);
        assertThat(fx.fetched).extracting(Object::toString).containsExactly(REMOTE + "se/rd/serde");
        assertThat(fx.storage.paths(proxy)).containsExactly("index/se/rd/serde");
        assertThat(CargoIndexes.upstreamIndexUri(proxy, "a").toString()).isEqualTo(REMOTE + "1/a");
        assertThat(CargoIndexes.upstreamIndexUri(proxy, "Syn").toString()).isEqualTo(REMOTE + "3/s/syn");
    }

    @Test
    void proxyDownloadsOnlyListedVersionsAndCachesThemOnce() {
        byte[] crate = CargoTestSupport.crate("serde", "1.0.0");
        fx.serve(REMOTE + "se/rd/serde", line("serde", "1.0.0", crate, false));
        fx.serve(STATIC + "serde/serde-1.0.0.crate", crate);

        StoredContent first = fx.indexes.crate(proxy, "serde", "1.0.0").orElseThrow();
        assertThat(first.asset().getPath()).isEqualTo("crates/serde/serde-1.0.0.crate");
        assertThat(first.asset().getComponentId()).isNotNull();
        assertThat(fx.fetchesOf(STATIC + "serde/serde-1.0.0.crate")).isEqualTo(1);

        assertThat(fx.indexes.crate(proxy, "serde", "1.0.0")).isPresent();
        assertThat(fx.fetchesOf(STATIC + "serde/serde-1.0.0.crate")).isEqualTo(1);

        // a version the index does not list never reaches the file host
        assertThat(fx.indexes.crate(proxy, "serde", "9.9.9")).isEmpty();
        assertThat(fx.fetched).noneMatch(u -> u.toString().contains("9.9.9"));
    }

    @Test
    void downloadTemplateSettingIsHonoured() {
        proxy.setFormatSettings(Map.of(CargoSettings.DOWNLOAD_URL, "https://mirror.test/dl"));
        byte[] crate = CargoTestSupport.crate("Inflector", "0.11.4");
        fx.serve(REMOTE + "in/fl/inflector", line("Inflector", "0.11.4", crate, false));
        fx.serve("https://mirror.test/dl/Inflector/0.11.4/download", crate);
        assertThat(fx.indexes.crate(proxy, "inflector", "0.11.4")).isPresent();
        assertThat(fx.storage.paths(proxy)).contains("crates/inflector/inflector-0.11.4.crate");
    }

    @Test
    void aDownloadWithTheWrongChecksumIsDiscarded() {
        byte[] crate = CargoTestSupport.crate("serde", "1.0.0");
        fx.serve(REMOTE + "se/rd/serde", line("serde", "1.0.0", crate, false));
        fx.serve(STATIC + "serde/serde-1.0.0.crate", "<html>captive portal</html>".getBytes());
        assertThatThrownBy(() -> fx.indexes.crate(proxy, "serde", "1.0.0"))
                .isInstanceOf(CargoError.class)
                .hasMessageContaining("checksum");
        assertThat(fx.storage.paths(proxy)).doesNotContain("crates/serde/serde-1.0.0.crate");
    }

    @Test
    void offlineProxyServesTheCacheOnly() {
        byte[] crate = CargoTestSupport.crate("serde", "1.0.0");
        fx.serve(REMOTE + "se/rd/serde", line("serde", "1.0.0", crate, false));
        fx.serve(STATIC + "serde/serde-1.0.0.crate", crate);
        fx.indexes.crate(proxy, "serde", "1.0.0").orElseThrow();
        fx.offline.add("cargo-proxy");
        fx.fetched.clear();
        assertThat(fx.indexes.entries(proxy, "serde")).hasSize(1);
        assertThat(fx.indexes.crate(proxy, "serde", "1.0.0")).isPresent();
        assertThat(fx.indexes.entries(proxy, "itoa")).isEmpty();
        assertThat(fx.fetched).isEmpty();
    }

    @Test
    void groupMergesHostedFirstAndDownloadsFromTheWinner() throws IOException {
        publish("acme", "1.0.0");
        byte[] upstreamCrate = CargoTestSupport.crate("acme", "0.5.0");
        byte[] shadow = CargoTestSupport.tarGz(Map.of("acme-1.0.0/Cargo.toml", "the public one"));
        fx.serve(REMOTE + "ac/me/acme", line("acme", "1.0.0", shadow, false) + "\n" + line("acme", "0.5.0", upstreamCrate, false));
        fx.serve(STATIC + "acme/acme-0.5.0.crate", upstreamCrate);
        fx.serve(STATIC + "acme/acme-1.0.0.crate", shadow);

        List<IndexEntry.Sourced<Repository>> merged = fx.indexes.entries(group, "acme");
        assertThat(merged).extracting(s -> s.entry().vers()).containsExactly("0.5.0", "1.0.0");
        assertThat(merged).extracting(s -> s.origin().getName()).containsExactly("cargo-proxy", "cargo-hosted");
        assertThat(fx.indexes.members(group)).extracting(Repository::getName).containsExactly("cargo-hosted", "cargo-proxy");

        StoredContent mine = fx.indexes.crate(group, "acme", "1.0.0").orElseThrow();
        assertThat(mine.asset().getBlobSha256()).isEqualTo(merged.get(1).entry().cksum());
        assertThat(fx.fetchesOf(STATIC + "acme/acme-1.0.0.crate")).isZero();

        StoredContent theirs = fx.indexes.crate(group, "acme", "0.5.0").orElseThrow();
        assertThat(theirs.asset().getBlobSha256()).isEqualTo(Checksums.hex("SHA-256", upstreamCrate));
        assertThat(fx.indexes.crate(group, "acme", "2.0.0")).isEmpty();
    }

    @Test
    void offlineMembersContributeNothing() throws IOException {
        publish("acme", "1.0.0");
        hosted.setOnline(false);
        assertThat(fx.indexes.entries(group, "acme")).isEmpty();
        assertThat(fx.indexes.crate(hosted, "acme", "1.0.0")).isEmpty();
    }
}
