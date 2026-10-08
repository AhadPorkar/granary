package dev.granary.format.npm;

import static dev.granary.format.npm.NpmTestSupport.MAPPER;
import static dev.granary.format.npm.NpmTestSupport.baseUrl;
import static dev.granary.format.npm.NpmTestSupport.hosted;
import static dev.granary.format.npm.NpmTestSupport.json;
import static dev.granary.format.npm.NpmTestSupport.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.repository.GroupResolver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class NpmSearchTest {

    private final FakeStorage storage = new FakeStorage();
    private final ComponentDao components = mock(ComponentDao.class);
    private final UpstreamClient upstream = mock(UpstreamClient.class);
    private final ProxyService proxy = mock(ProxyService.class);
    private final GroupResolver groups = mock(GroupResolver.class);
    private final NpmPackuments packuments = new NpmPackuments(storage.mock, proxy, upstream, groups, MAPPER);
    private final NpmSearch search = new NpmSearch(packuments, components, upstream, proxy, groups);
    private final Repository repo = hosted("npm-hosted");

    @Test
    void hostedSearchListsEachMatchingPackageOnce() {
        stubComponents(repo, "lo", component("", "lodash", "4.17.20"), component("", "lodash", "4.17.21"),
                component("acme", "lo-ui", "1.0.0"));
        storePackument(repo, "lodash", "4.17.21", "Lodash modular utilities.");
        storePackument(repo, "@acme/lo-ui", "1.0.0", "UI bits");

        ObjectNode result = search.search(repo, "lo", 0, 20, baseUrl(repo));

        assertThat(result.path("total").asLong()).isEqualTo(2);
        assertThat(names(result)).containsExactly("lodash", "@acme/lo-ui");
        JsonNode lodash = result.path("objects").get(0).path("package");
        assertThat(str(lodash, "version")).isEqualTo("4.17.21");
        assertThat(str(lodash, "description")).isEqualTo("Lodash modular utilities.");
        assertThat(str(lodash, "scope")).isEqualTo("unscoped");
        assertThat(str(lodash, "links", "npm")).isEqualTo(baseUrl(repo) + "/lodash");
        assertThat(str(lodash, "date")).isNotNull();
        assertThat(lodash.path("maintainers").isArray()).isTrue();
        assertThat(str(result.path("objects").get(1).path("package"), "scope")).isEqualTo("acme");
        assertThat(result.path("objects").get(0).path("score").path("final").asDouble()).isEqualTo(1.0);
        assertThat(str(result, "time")).isNotNull();
    }

    @Test
    void hostedSearchRequiresEveryWordAndIgnoresQualifiers() {
        stubComponents(repo, "lo", component("", "lodash", "4.17.21"), component("acme", "lo-ui", "1.0.0"));
        storePackument(repo, "lodash", "4.17.21", "x");
        storePackument(repo, "@acme/lo-ui", "1.0.0", "x");

        assertThat(names(search.search(repo, "lo ui author:bob", 0, 20, baseUrl(repo)))).containsExactly("@acme/lo-ui");
    }

    @Test
    void hostedSearchPaginates() {
        stubComponents(repo, "", component("", "a", "1.0.0"), component("", "b", "1.0.0"), component("", "c", "1.0.0"));
        for (String name : List.of("a", "b", "c")) {
            storePackument(repo, name, "1.0.0", "x");
        }
        ObjectNode page = search.search(repo, "", 1, 1, baseUrl(repo));
        assertThat(page.path("total").asLong()).isEqualTo(3);
        assertThat(names(page)).containsExactly("b");
        assertThat(names(search.search(repo, "", 5, 10, baseUrl(repo)))).isEmpty();
    }

    @Test
    void proxySearchIsEmptyWhenOffline() {
        Repository proxyRepo = NpmTestSupport.proxy("npm-proxy", "https://registry.npmjs.org/");
        when(proxy.isOffline(proxyRepo)).thenReturn(true);
        ObjectNode result = search.search(proxyRepo, "lodash", 0, 20, baseUrl(proxyRepo));
        assertThat(result.path("total").asLong()).isZero();
        assertThat(result.path("objects").size()).isZero();
    }

    @Test
    void proxySearchForwardsToUpstreamWithoutCaching() throws Exception {
        Repository proxyRepo = NpmTestSupport.proxy("npm-proxy", "https://registry.npmjs.org");
        String upstreamJson = "{\"objects\":[{\"package\":{\"name\":\"lodash\",\"version\":\"4.17.21\"}}],\"total\":1,\"time\":\"now\"}";
        UpstreamClient.Response response = mock(UpstreamClient.Response.class);
        when(response.status()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(upstreamJson.getBytes(StandardCharsets.UTF_8)));
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        when(upstream.getAbsolute(eq(proxyRepo), uri.capture(), anyMap(), anyMap())).thenReturn(response);

        ObjectNode result = search.search(proxyRepo, "lo dash", 5, 2, baseUrl(proxyRepo));

        assertThat(uri.getValue().toString()).isEqualTo("https://registry.npmjs.org/-/v1/search?text=lo+dash&size=2&from=5");
        assertThat(names(result)).containsExactly("lodash");
        assertThat(result.path("total").asLong()).isEqualTo(1);
        assertThat(storage.paths(proxyRepo)).isEmpty();
    }

    @Test
    void proxySearchSwallowsUpstreamFailures() throws Exception {
        Repository proxyRepo = NpmTestSupport.proxy("npm-proxy", "https://registry.npmjs.org/");
        when(upstream.getAbsolute(eq(proxyRepo), any(), anyMap(), anyMap())).thenThrow(new IOException("boom"));
        assertThat(search.search(proxyRepo, "x", 0, 20, baseUrl(proxyRepo)).path("total").asLong()).isZero();

        UpstreamClient.Response failing = mock(UpstreamClient.Response.class);
        when(failing.status()).thenReturn(503);
        when(upstream.getAbsolute(eq(proxyRepo), any(), anyMap(), anyMap())).thenReturn(failing);
        assertThat(search.search(proxyRepo, "x", 0, 20, baseUrl(proxyRepo)).path("total").asLong()).isZero();
    }

    @Test
    void groupSearchUnionsMembersWithTheFirstWinning() throws Exception {
        Repository proxyRepo = NpmTestSupport.proxy("npm-proxy", "https://registry.npmjs.org/");
        Repository group = NpmTestSupport.group("npm-all", "npm-hosted", "npm-proxy");
        when(groups.leafMembers(group)).thenReturn(List.of(repo, proxyRepo));
        stubComponents(repo, "lo", component("", "lodash", "9.0.0-internal"));
        storePackument(repo, "lodash", "9.0.0-internal", "our fork");
        UpstreamClient.Response response = mock(UpstreamClient.Response.class);
        when(response.status()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(
                "{\"objects\":[{\"package\":{\"name\":\"lodash\",\"version\":\"4.17.21\"}},{\"package\":{\"name\":\"lodash-es\",\"version\":\"4.17.21\"}}],\"total\":2}"
                        .getBytes(StandardCharsets.UTF_8)));
        when(upstream.getAbsolute(eq(proxyRepo), any(), anyMap(), anyMap())).thenReturn(response);

        ObjectNode result = search.search(group, "lo", 0, 20, baseUrl(group));

        assertThat(names(result)).containsExactly("lodash", "lodash-es");
        assertThat(str(result.path("objects").get(0).path("package"), "version")).isEqualTo("9.0.0-internal");
        assertThat(result.path("total").asLong()).isEqualTo(2);
    }

    private void stubComponents(Repository repository, String term, Component... found) {
        when(components.search(eq(repository.getId()), eq(term), any())).thenReturn(new PageImpl<>(List.of(found)));
    }

    private static Component component(String namespace, String name, String version) {
        Component c = new Component();
        c.setNamespace(namespace);
        c.setName(name);
        c.setVersion(version);
        return c;
    }

    private void storePackument(Repository repository, String name, String version, String description) {
        ObjectNode doc = NpmPackument.newDocument(name);
        doc.put("description", description);
        NpmPackument.putVersion(doc, version, json("{\"version\":\"" + version + "\"}"), java.time.Instant.now());
        NpmPackument.distTags(doc).put("latest", version);
        storage.put(repository, name, MAPPER.writeValueAsBytes(doc), "application/json", true);
    }

    private static List<String> names(JsonNode result) {
        List<String> names = new ArrayList<>();
        for (JsonNode o : result.path("objects")) {
            names.add(str(o.path("package"), "name"));
        }
        return names;
    }
}
