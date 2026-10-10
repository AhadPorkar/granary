package dev.granary.format.raw;

import static dev.granary.format.raw.RawTestSupport.body;
import static dev.granary.format.raw.RawTestSupport.context;
import static dev.granary.format.raw.RawTestSupport.hosted;
import static dev.granary.format.raw.RawTestSupport.json;
import static dev.granary.format.raw.RawTestSupport.properties;
import static dev.granary.format.raw.RawTestSupport.proxy;
import static dev.granary.format.raw.RawTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

class BowerFormatHandlerTest {

    @TempDir
    Path dir;

    final FakeStorage storage = new FakeStorage();
    final ProxyService proxy = mock(ProxyService.class);
    final GroupResolver groups = mock(GroupResolver.class);
    final Repository hosted = hosted("bower-hosted", "bower");
    final Repository registry = proxy("bower-registry", "bower", "https://registry.bower.io/");
    BowerFormatHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BowerFormatHandler(storage.mock, proxy, groups, properties(dir));
    }

    static RepositoryRequest register(Repository repo, String name, String url) {
        RepositoryRequest request = request(repo, "POST", "packages", new byte[0], "application/x-www-form-urlencoded");
        MockHttpServletRequest http = (MockHttpServletRequest) request.http();
        if (name != null) {
            http.setParameter("name", name);
        }
        if (url != null) {
            http.setParameter("url", url);
        }
        return request;
    }

    @Test
    void hostedRegistryCrud() throws Exception {
        ResponseEntity<?> registered = handler.handle(register(hosted, "jquery", "https://github.com/jquery/jquery.git"));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(registered).path("name").asString()).isEqualTo("jquery");
        assertThat(storage.asset(hosted, "packages/jquery").isMetadata()).isTrue();
        assertThat(storage.coordinates(hosted, "packages/jquery")).isNull();
        handler.handle(register(hosted, "angular", "git://github.com/angular/bower-angular.git"));

        JsonNode list = json(handler.handle(request(hosted, "GET", "packages")));
        assertThat(list).hasSize(2);
        assertThat(list.get(0).path("name").asString()).isEqualTo("angular");
        assertThat(list.get(1).path("url").asString()).isEqualTo("https://github.com/jquery/jquery.git");

        ResponseEntity<?> one = handler.handle(request(hosted, "GET", "packages/jquery"));
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(one.getHeaders().getContentType()).hasToString("application/json");
        assertThat(json(one).path("url").asString()).isEqualTo("https://github.com/jquery/jquery.git");

        JsonNode search = json(handler.handle(request(hosted, "GET", "packages/search/JQ")));
        assertThat(search).hasSize(1);
        assertThat(search.get(0).path("name").asString()).isEqualTo("jquery");
        assertThat(json(handler.handle(request(hosted, "GET", "packages/search/nothing")))).isEmpty();

        assertThat(handler.handle(request(hosted, "DELETE", "packages/jquery")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(handler.handle(request(hosted, "GET", "packages/jquery")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(handler.handle(request(hosted, "GET", "packages")))).hasSize(1);
    }

    @Test
    void registrationValidatesAndRefusesToTakeOverANameUnlessIdentical() throws Exception {
        handler.handle(register(hosted, "jquery", "https://github.com/jquery/jquery.git"));
        ResponseEntity<?> same = handler.handle(register(hosted, "jquery", "https://github.com/jquery/jquery.git"));
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<?> taken = handler.handle(register(hosted, "jquery", "https://evil.example.com/jquery.git"));
        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(taken).path("code").asString()).isEqualTo("bower.nameTaken");
        assertThat(json(handler.handle(request(hosted, "GET", "packages/jquery"))).path("url").asString()).isEqualTo("https://github.com/jquery/jquery.git");

        hosted.setAllowRedeploy(true);
        assertThat(handler.handle(register(hosted, "jquery", "https://fork.example.com/jquery.git")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(handler.handle(request(hosted, "GET", "packages/jquery"))).path("url").asString()).isEqualTo("https://fork.example.com/jquery.git");

        ResponseEntity<?> badName = handler.handle(register(hosted, "../x", "https://github.com/x/x.git"));
        assertThat(badName.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(badName).path("code").asString()).isEqualTo("bower.invalidName");
        ResponseEntity<?> badUrl = handler.handle(register(hosted, "ok", "not a url"));
        assertThat(badUrl.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(badUrl).path("code").asString()).isEqualTo("bower.invalidUrl");
        assertThat(handler.handle(register(hosted, null, null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void jsonRegistrationForScripts() throws Exception {
        ResponseEntity<?> registered = handler.handle(request(hosted, "POST", "packages",
                "{\"name\":\"lodash\",\"url\":\"git+https://github.com/lodash/lodash.git\"}".getBytes(StandardCharsets.UTF_8), "application/json"));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(handler.handle(request(hosted, "GET", "packages/lodash"))).path("url").asString()).isEqualTo("git+https://github.com/lodash/lodash.git");
        ResponseEntity<?> malformed = handler.handle(request(hosted, "POST", "packages", "{not json".getBytes(StandardCharsets.UTF_8), "application/json"));
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(malformed).path("code").asString()).isEqualTo("bower.malformed");
        assertThat(handler.handle(request(hosted, "PUT", "packages", "x")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hosted, "POST", "packages/search/x", "x")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void jsonRegistrationIsRecognisedWhateverTheServerLocale() throws Exception {
        java.util.Locale previous = java.util.Locale.getDefault();
        // in Turkish "APPLICATION/JSON".toLowerCase() has dotless i's and no longer starts with "application/json"
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        try {
            ResponseEntity<?> registered = handler.handle(request(hosted, "POST", "packages",
                    "{\"name\":\"tr-widget\",\"url\":\"https://git.example.com/tr-widget.git\"}".getBytes(StandardCharsets.UTF_8),
                    "APPLICATION/JSON; charset=UTF-8"));
            assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void proxyAnswersFromTheUpstreamRegistryAndGroupsMerge() throws Exception {
        when(proxy.fetch(eq(registry), eq("packages"), any(FetchOptions.class))).thenReturn(Optional.of(storage.put(registry, "packages",
                "[{\"name\":\"jquery\",\"url\":\"https://github.com/jquery/jquery.git\"},{\"name\":\"zepto\",\"url\":\"https://github.com/madrobby/zepto.git\"}]"
                        .getBytes(StandardCharsets.UTF_8), "application/json", true)));
        when(proxy.fetch(eq(registry), eq("packages/search/jq"), any(FetchOptions.class))).thenReturn(Optional.of(storage.put(registry, "packages/search/jq",
                "[{\"name\":\"jquery\",\"url\":\"https://github.com/jquery/jquery.git\"}]".getBytes(StandardCharsets.UTF_8), "application/json", true)));
        when(proxy.fetch(eq(registry), eq("packages/jquery"), any(FetchOptions.class))).thenReturn(Optional.of(storage.put(registry, "packages/jquery",
                "{\"name\":\"jquery\",\"url\":\"https://github.com/jquery/jquery.git\"}".getBytes(StandardCharsets.UTF_8), "application/json", true)));

        assertThat(json(handler.handle(request(registry, "GET", "packages")))).hasSize(2);
        assertThat(json(handler.handle(request(registry, "GET", "packages/search/jq")))).hasSize(1);
        assertThat(json(handler.handle(request(registry, "GET", "packages/jquery"))).path("name").asString()).isEqualTo("jquery");
        ResponseEntity<?> post = handler.handle(register(registry, "x", "https://github.com/x/x.git"));
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(json(post).path("code").asString()).isEqualTo("repository.readOnly");

        handler.handle(register(hosted, "jquery", "https://internal.example.com/jquery.git"));
        handler.handle(register(hosted, "widget", "https://internal.example.com/widget.git"));
        Repository group = RawTestSupport.group("bower-all", "bower", "bower-hosted", "bower-registry");
        when(groups.leafMembers(group)).thenReturn(List.of(hosted, registry));
        JsonNode merged = json(handler.handle(request(group, "GET", "packages")));
        assertThat(merged).extracting(n -> n.path("name").asString()).containsExactly("jquery", "widget", "zepto");
        assertThat(merged.get(0).path("url").asString()).isEqualTo("https://internal.example.com/jquery.git");
        assertThat(body(handler.handle(request(group, "GET", "packages/jquery")))).contains("internal.example.com");
        assertThat(handler.handle(register(group, "y", "https://github.com/y/y.git")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void metadataTemplatesAndSnippets() {
        assertThat(handler.isMetadataPath("packages")).isTrue();
        assertThat(handler.isMetadataPath("packages/jquery")).isTrue();
        assertThat(handler.isMetadataPath("packages/search/jq")).isTrue();
        assertThat(handler.isMetadataPath("dist/jquery.zip")).isFalse();
        assertThat(handler.templates()).extracting(RepositoryTemplate::name).containsExactly("bower-registry");
        assertThat(handler.templates().get(0).remoteUrl()).isEqualTo("https://registry.bower.io/");
        var snippets = handler.connectSnippets(context(registry, true));
        assertThat(snippets).extracting(ConnectSnippet::id).containsExactly("bowerrc", "install", "register", "lookup");
        ConnectSnippet bowerrc = snippets.get(0);
        assertThat(bowerrc.filename()).isEqualTo(".bowerrc");
        assertThat(bowerrc.code()).contains("\"registry\": \"http://granary.test/repository/bower-registry\"").contains("\"storage\"");
        assertThat(snippets.get(1).code()).contains("npm install -g bower").contains("--config.registry=http://granary.test/repository/bower-registry");
    }
}
