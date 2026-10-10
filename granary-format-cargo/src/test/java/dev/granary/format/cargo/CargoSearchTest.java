package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.node.ObjectNode;

class CargoSearchTest {

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
        proxy = CargoTestSupport.proxy("cargo-proxy", "https://index.crates.io/");
        group = CargoTestSupport.group("cargo-group", "cargo-hosted", "cargo-proxy");
        fx.group(group, hosted, proxy);
    }

    private void publish(String name, String version, String description) throws IOException {
        ObjectNode meta = CargoTestSupport.metadata(name, version);
        meta.put("description", description);
        byte[] body = CargoTestSupport.publishBody(meta, CargoTestSupport.crate(name, version));
        try (PublishRequest request = PublishRequest.read(new ByteArrayInputStream(body), temp.resolve("tmp"))) {
            fx.hosted.publish(hosted, request, PublishMetadata.of(request.metadata()), "alice");
        }
    }

    private void upstreamAnswers(int status, String body) throws Exception {
        UpstreamClient.Response response = mock(UpstreamClient.Response.class);
        when(response.status()).thenReturn(status);
        when(response.body()).thenAnswer(inv -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        doReturn(response).when(fx.upstreamClient).getAbsolute(any(), any(), anyMap(), anyMap());
    }

    @Test
    void hostedSearchMatchesNameDescriptionAndKeywords() throws IOException {
        publish("acme-hello", "0.1.0", "Says hello");
        publish("acme-hello", "0.2.0", "Says hello louder");
        publish("acme", "1.0.0", "The root crate");
        publish("widgets", "2.0.0", "Unrelated");

        CargoSearch.Result result = fx.search.search(hosted, "acme", 10, 1);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.hits()).extracting(CargoSearch.Hit::name).containsExactly("acme", "acme-hello");
        assertThat(result.hits().get(1).maxVersion()).isEqualTo("0.2.0");
        assertThat(result.hits().get(1).description()).isEqualTo("Says hello louder");

        assertThat(fx.search.search(hosted, "LOUDER", 10, 1).hits()).extracting(CargoSearch.Hit::name).containsExactly("acme-hello");
        assertThat(fx.search.search(hosted, "acme_hello", 10, 1).hits()).extracting(CargoSearch.Hit::name).containsExactly("acme-hello");
        assertThat(fx.search.search(hosted, "greeting", 10, 1).total()).isEqualTo(3);
        assertThat(fx.search.search(hosted, "", 10, 1).total()).isEqualTo(3);
        assertThat(fx.search.search(hosted, null, 2, 2).hits()).extracting(CargoSearch.Hit::name).containsExactly("widgets");
        assertThat(fx.search.search(hosted, "nothing-like-this", 10, 1).hits()).isEmpty();
    }

    @Test
    void maxVersionSkipsYankedAndPrereleases() throws IOException {
        publish("acme", "1.0.0", "x");
        publish("acme", "1.1.0", "x");
        publish("acme", "2.0.0-rc.1", "x");
        fx.hosted.setYanked(hosted, "acme", "1.1.0", true);
        assertThat(fx.search.search(hosted, "acme", 10, 1).hits().getFirst().maxVersion()).isEqualTo("1.0.0");
    }

    @Test
    void hitPicksTheBestVersion() {
        List<IndexEntry> entries = IndexEntry.parseFile(String.join("\n",
                IndexEntryTest.line("Acme", "1.0.0", true), IndexEntryTest.line("Acme", "0.9.0", true)));
        assertThat(CargoSearch.hit(entries, null)).isEqualTo(new CargoSearch.Hit("Acme", "1.0.0", null));
    }

    @Test
    void proxyAsksTheUpstreamApi() throws Exception {
        upstreamAnswers(200, "{\"crates\":[{\"name\":\"itoa\",\"max_version\":\"1.0.18\",\"description\":\"Fast\"},"
                + "{\"name\":\"zeroes_itoa\",\"newest_version\":\"0.1.0\",\"description\":null},{\"nope\":1}],"
                + "\"meta\":{\"total\":42}}");
        CargoSearch.Result result = fx.search.search(proxy, "itoa fast", 2, 3);
        assertThat(result.total()).isEqualTo(42);
        assertThat(result.hits()).containsExactly(new CargoSearch.Hit("itoa", "1.0.18", "Fast"),
                new CargoSearch.Hit("zeroes_itoa", "0.1.0", null));
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(fx.upstreamClient).getAbsolute(any(), uri.capture(), anyMap(), anyMap());
        assertThat(uri.getValue().toString()).isEqualTo("https://crates.io/api/v1/crates?q=itoa+fast&per_page=2&page=3");
    }

    @Test
    void proxyFallsBackToTheCacheWhenTheApiFailsOrIsOff() throws Exception {
        fx.storage.put(proxy, "index/it/oa/itoa", IndexEntryTest.line("itoa", "1.0.18", false) + "\n", true);
        upstreamAnswers(503, "down");
        assertThat(fx.search.search(proxy, "itoa", 10, 1).hits()).containsExactly(new CargoSearch.Hit("itoa", "1.0.18", null));

        doThrow(new IOException("refused")).when(fx.upstreamClient).getAbsolute(any(), any(), anyMap(), anyMap());
        assertThat(fx.search.search(proxy, "itoa", 10, 1).total()).isEqualTo(1);

        upstreamAnswers(200, "not json");
        assertThat(fx.search.search(proxy, "itoa", 10, 1).total()).isEqualTo(1);
    }

    @Test
    void offlineOrUnhealthyProxiesDoNotAskUpstream() throws Exception {
        fx.offline.add("cargo-proxy");
        fx.search.search(proxy, "itoa", 10, 1);
        fx.offline.clear();
        when(fx.proxy.status(any())).thenReturn(new UpstreamHealth.Status(false, 3, "down", null, null, null));
        fx.search.search(proxy, "itoa", 10, 1);
        proxy.setFormatSettings(Map.of(CargoSettings.API_URL, "none"));
        when(fx.proxy.status(any())).thenReturn(new UpstreamHealth.Status(true, 0, null, null, null, null));
        fx.search.search(proxy, "itoa", 10, 1);
        verify(fx.upstreamClient, never()).getAbsolute(any(), any(), anyMap(), anyMap());
    }

    @Test
    void groupMergesHostedFirstWithOneHitPerCrate() throws Exception {
        publish("itoa", "9.0.0", "Our fork");
        publish("acme", "1.0.0", "itoa user");
        upstreamAnswers(200, "{\"crates\":[{\"name\":\"itoa\",\"max_version\":\"1.0.18\",\"description\":\"Fast\"},"
                + "{\"name\":\"itoap\",\"max_version\":\"1.0.1\",\"description\":\"Faster\"}],\"meta\":{\"total\":5}}");
        CargoSearch.Result result = fx.search.search(group, "itoa", 10, 1);
        assertThat(result.hits()).extracting(CargoSearch.Hit::name).containsExactly("itoa", "acme", "itoap");
        assertThat(result.hits().getFirst().maxVersion()).isEqualTo("9.0.0");
        // 2 hosted + 5 upstream, minus the duplicate itoa
        assertThat(result.total()).isEqualTo(6);
        assertThat(fx.search.search(group, "itoa", 2, 2).hits()).extracting(CargoSearch.Hit::name).containsExactly("itoap");
    }

    @Test
    void renderHasTheCratesIoShape() {
        ObjectNode body = CargoSearch.render(new CargoSearch.Result(List.of(new CargoSearch.Hit("a", "1.0.0", "x"),
                new CargoSearch.Hit("b", "2.0.0", null)), 7));
        assertThat(CargoJson.write(body)).isEqualTo("{\"crates\":[{\"name\":\"a\",\"max_version\":\"1.0.0\",\"description\":\"x\"},"
                + "{\"name\":\"b\",\"max_version\":\"2.0.0\",\"description\":null}],\"meta\":{\"total\":7}}");
    }

    @Test
    void parseResultIsLenient() {
        CargoSearch.Result r = CargoSearch.parseResult(CargoJson.parseObject("{\"crates\":[{\"name\":\"x\"}]}").orElseThrow());
        assertThat(r.hits()).containsExactly(new CargoSearch.Hit("x", "0.0.0", null));
        assertThat(r.total()).isEqualTo(1);
        assertThat(CargoSearch.parseResult(CargoJson.object()).hits()).isEmpty();
    }

    @Test
    void termsAndSlices() {
        assertThat(CargoSearch.terms("  Foo_Bar   baz ")).containsExactly("foo-bar", "baz");
        assertThat(CargoSearch.terms(null)).isEmpty();
        List<CargoSearch.Hit> hits = List.of(new CargoSearch.Hit("a", "1.0.0", null), new CargoSearch.Hit("b", "1.0.0", null),
                new CargoSearch.Hit("c", "1.0.0", null));
        assertThat(CargoSearch.slice(hits, 2, 1).hits()).extracting(CargoSearch.Hit::name).containsExactly("a", "b");
        assertThat(CargoSearch.slice(hits, 2, 2).hits()).extracting(CargoSearch.Hit::name).containsExactly("c");
        assertThat(CargoSearch.slice(hits, 2, 9).hits()).isEmpty();
        assertThat(CargoSearch.slice(hits, 2, 9).total()).isEqualTo(3);
        assertThat(Optional.of(CargoSearch.slice(List.of(), 10, 1).total())).contains(0L);
    }
}
