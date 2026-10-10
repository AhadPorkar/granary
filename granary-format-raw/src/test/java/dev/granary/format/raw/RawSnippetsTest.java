package dev.granary.format.raw;

import static dev.granary.format.raw.RawTestSupport.context;
import static dev.granary.format.raw.RawTestSupport.hosted;
import static dev.granary.format.raw.RawTestSupport.properties;
import static dev.granary.format.raw.RawTestSupport.proxy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Every profile: templates, settings and connect snippets are well formed. */
class RawSnippetsTest {

    static Stream<RawFormatHandler> handlers() {
        StorageService storage = mock(StorageService.class);
        ProxyService proxy = mock(ProxyService.class);
        GroupResolver groups = mock(GroupResolver.class);
        GranaryProperties properties = properties(Path.of("/tmp/granary-raw-snippets"));
        return Stream.of(
                new RawGenericFormatHandler(storage, proxy, groups, properties),
                new CdnFormatHandler(storage, proxy, groups, properties),
                new CpanFormatHandler(storage, proxy, groups, properties),
                new CranFormatHandler(storage, proxy, groups, properties),
                new HackageFormatHandler(storage, proxy, groups, properties),
                new VcpkgFormatHandler(storage, proxy, groups, properties),
                new BowerFormatHandler(storage, proxy, groups, properties),
                new ElmFormatHandler(storage, proxy, groups, properties));
    }

    @ParameterizedTest
    @MethodSource("handlers")
    void templatesBelongToTheProfileAndValidate(RawFormatHandler handler) {
        assertThat(handler.templates()).isNotEmpty();
        Set<String> names = new HashSet<>();
        for (RepositoryTemplate template : handler.templates()) {
            assertThat(names.add(template.name())).as("duplicate template %s", template.name()).isTrue();
            assertThat(template.format()).isEqualTo(handler.format());
            assertThat(template.description()).isNotBlank();
            assertThat(handler.supportedTypes()).contains(template.type());
            if (template.type() == RepositoryType.PROXY) {
                assertThat(template.remoteUrl()).startsWith("https://").endsWith("/");
                handler.validate(proxy(template.name(), handler.format(), template.remoteUrl()));
            } else {
                assertThat(template.remoteUrl()).isNull();
                handler.validate(hosted(template.name(), handler.format()));
            }
        }
        assertThat(handler.displayName()).isNotBlank();
        assertThat(handler.settings()).extracting(SettingDescriptor::key)
                .containsExactlyInAnyOrder("raw.metadataPatterns", "raw.contentDisposition", "raw.listingEnabled");
    }

    @ParameterizedTest
    @MethodSource("handlers")
    void snippetsAreCompleteForEveryTemplateAndAuthMode(RawFormatHandler handler) {
        List<Repository> repositories = handler.templates().stream()
                .map(t -> t.type() == RepositoryType.PROXY
                        ? proxy(t.name(), handler.format(), t.remoteUrl())
                        : hosted(t.name(), handler.format()))
                .toList();
        for (Repository repo : repositories) {
            for (boolean anonymousRead : new boolean[] {true, false}) {
                ConnectContext ctx = context(repo, anonymousRead);
                List<ConnectSnippet> snippets = handler.connectSnippets(ctx);
                assertThat(snippets).as("%s snippets", repo.getName()).isNotEmpty();
                Set<String> ids = new HashSet<>();
                boolean mentionsUrl = false;
                for (ConnectSnippet snippet : snippets) {
                    assertThat(snippet.id()).matches("[a-z0-9-]+");
                    assertThat(ids.add(snippet.id())).as("duplicate snippet id %s in %s", snippet.id(), handler.format()).isTrue();
                    assertThat(snippet.title()).isNotBlank();
                    assertThat(snippet.language()).isNotBlank();
                    assertThat(snippet.code()).isNotBlank().doesNotContain("null");
                    mentionsUrl |= snippet.code().contains(ctx.url());
                    if (!anonymousRead) {
                        assertThat(snippet.code()).doesNotContain("admin123");
                    }
                }
                assertThat(mentionsUrl).as("%s snippets mention the repository URL", repo.getName()).isTrue();
                if (!anonymousRead) {
                    assertThat(snippets.stream().anyMatch(s -> s.code().contains(ConnectContext.TOKEN_PLACEHOLDER)))
                            .as("%s snippets show how to authenticate", repo.getName()).isTrue();
                }
            }
        }
    }
}
