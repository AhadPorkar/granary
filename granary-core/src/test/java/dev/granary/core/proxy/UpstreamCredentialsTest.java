package dev.granary.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.GranaryException;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.FormatRegistry;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

class UpstreamCredentialsTest {

    private static Repository proxy(String remoteUrl, String hosts) {
        Repository repo = new Repository();
        repo.setName("p");
        repo.setFormat("raw");
        repo.setType(RepositoryType.PROXY);
        repo.setRemoteUrl(remoteUrl);
        repo.setRemoteUsername("mirror");
        repo.setRemotePassword("s3cret");
        if (hosts != null) {
            repo.setFormatSettings(Map.of(UpstreamCredentials.HOSTS_SETTING, hosts));
        }
        return repo;
    }

    private static boolean permitted(String remoteUrl, String target) {
        return UpstreamCredentials.permitted(proxy(remoteUrl, null), URI.create(target));
    }

    private static boolean permitted(String remoteUrl, String hosts, String target) {
        return UpstreamCredentials.permitted(proxy(remoteUrl, hosts), URI.create(target));
    }

    @Test
    void theRemoteUrlsOwnOriginReceivesTheCredentialsWhateverThePath() {
        assertThat(permitted("https://repo.example.com/maven2/", "https://repo.example.com/maven2/a.jar")).isTrue();
        assertThat(permitted("https://repo.example.com/maven2/", "https://repo.example.com/other/path?x=1#y")).isTrue();
        assertThat(permitted("http://mirror.internal:8081/repo", "http://mirror.internal:8081/download/a.zip")).isTrue();
    }

    @Test
    void otherHostsSubdomainsAndParentDomainsNeverDo() {
        String remote = "https://repo.example.com/";
        assertThat(permitted(remote, "https://github.com/acme/chart/releases/download/v1/chart-1.0.0.tgz")).isFalse();
        assertThat(permitted(remote, "https://cdn.repo.example.com/a.tgz")).isFalse();
        assertThat(permitted(remote, "https://example.com/a.tgz")).isFalse();
        assertThat(permitted(remote, "https://repo.example.com.evil.test/a.tgz")).isFalse();
        assertThat(permitted(remote, "https://evilrepo.example.com/a.tgz")).isFalse();
    }

    @Test
    void defaultPortsAreTheSameOriginAndOtherPortsAreNot() {
        assertThat(permitted("https://repo.example.com/", "https://repo.example.com:443/a")).isTrue();
        assertThat(permitted("https://repo.example.com:443/", "https://repo.example.com/a")).isTrue();
        assertThat(permitted("http://repo.example.com/", "http://repo.example.com:80/a")).isTrue();
        assertThat(permitted("https://repo.example.com/", "https://repo.example.com:8443/a")).isFalse();
        assertThat(permitted("https://repo.example.com:8443/", "https://repo.example.com/a")).isFalse();
        assertThat(permitted("http://localhost:8081/", "http://localhost:8082/a")).isFalse();
    }

    @Test
    void schemeAndHostAreComparedWithoutRegardToCase() {
        assertThat(permitted("https://Repo.Example.COM/", "HTTPS://REPO.example.com/a")).isTrue();
        assertThat(permitted("HTTP://Mirror.Internal/", "http://mirror.internal/a")).isTrue();
    }

    @Test
    void aTrailingDotIsTheSameFullyQualifiedHostButEmptyLabelsAreNothing() {
        assertThat(permitted("https://repo.example.com/", "https://repo.example.com./a")).isTrue();
        assertThat(permitted("https://repo.example.com./", "https://repo.example.com/a")).isTrue();
        assertThat(UpstreamCredentials.Origin.of("https://repo.example.com../a")).isEmpty();
        assertThat(permitted("https://repo.example.com/", "https://repo.example.com../a")).isFalse();
    }

    @Test
    void theHostOfAUrlWithUserInfoIsTheOneAfterTheAtSign() {
        assertThat(permitted("https://repo.example.com/", "https://repo.example.com@evil.test/a")).isFalse();
        assertThat(permitted("https://repo.example.com/", "https://user:pw@repo.example.com/a")).isTrue();
        assertThat(permitted("https://user:pw@repo.example.com/", "https://repo.example.com/a")).isTrue();
        assertThat(permitted("https://repo.example.com/", "https://repo.example.com:443@evil.test/a")).isFalse();
    }

    @Test
    void internationalisedNamesMatchOnlyInTheirAsciiForm() {
        // java.net.URI cannot read a host that is not ASCII, so such a URL has no origin and never matches
        assertThat(UpstreamCredentials.Origin.of("https://bücher.example/a")).isEmpty();
        assertThat(permitted("https://xn--bcher-kva.example/", "https://bücher.example/a")).isFalse();
        assertThat(permitted("https://xn--bcher-kva.example/", "https://XN--BCHER-KVA.example/a")).isTrue();
        // the setting may name the host in either form
        assertThat(permitted("https://repo.example.com/", "bücher.example", "https://xn--bcher-kva.example/a")).isTrue();
        assertThat(permitted("https://repo.example.com/", "xn--bcher-kva.example", "https://xn--bcher-kva.example/a")).isTrue();
        assertThat(permitted("https://repo.example.com/", "bucher.example", "https://xn--bcher-kva.example/a")).isFalse();
        assertThat(permitted("https://repo.example.com/", "BÜCHER.example.", "https://xn--bcher-kva.example/a")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"faß.de", "*.faß.de", "ςόλος.gr", "a\u200db.example", "\uff45xample.com", "bücher\u3002example"})
    void internationalisedNamesThatIdna2003WouldTurnIntoAnotherHostAreRefused(String entry) {
        // the JDK's IDNA 2003 conversion maps ß to ss, a final sigma to sigma, drops joiners and folds full-width and
        // ideographic forms, so these would grant the credentials to another domain than the one resolvers look up
        assertThatThrownBy(() -> UpstreamCredentials.parse(entry))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("xn--");
        Repository repo = proxy("https://mirror.internal/", entry);
        assertThatThrownBy(() -> UpstreamCredentials.validate(repo)).isInstanceOf(GranaryException.class);
        assertThat(UpstreamCredentials.trustedHosts(repo)).isEmpty();
        assertThat(UpstreamCredentials.permitted(repo, URI.create("https://fass.de/a"))).isFalse();
        // the xn-- form of the IDNA 2008 name is accepted and means exactly that name
        assertThat(permitted("https://mirror.internal/", "xn--fa-hia.de", "https://xn--fa-hia.de/a")).isTrue();
        assertThat(permitted("https://mirror.internal/", "xn--fa-hia.de", "https://fass.de/a")).isFalse();
    }

    @Test
    void ipv6AddressesAreComparedInCanonicalForm() {
        assertThat(permitted("http://[::1]:8081/", "http://[0:0:0:0:0:0:0:1]:8081/a")).isTrue();
        assertThat(permitted("http://[2001:DB8::1]/", "http://[2001:db8:0:0:0:0:0:1]/a")).isTrue();
        assertThat(permitted("http://[::1]:8081/", "http://[::2]:8081/a")).isFalse();
        assertThat(permitted("http://[::1]:8081/", "http://[::1]:8082/a")).isFalse();
        assertThat(permitted("http://127.0.0.1:8081/", "http://[::1]:8081/a")).isFalse();
        assertThat(permitted("http://repo.example.com/", "[2001:db8::1]:8080", "http://[2001:db8:0::1]:8080/a")).isTrue();
    }

    @Test
    void ipv4ShorthandsAndLeadingZerosNeverMatch() {
        assertThat(permitted("http://127.0.0.1:8081/", "http://127.0.0.1:8081/a")).isTrue();
        assertThat(permitted("http://127.0.0.1:8081/", "http://127.1:8081/a")).isFalse();
        assertThat(permitted("http://127.0.0.1:8081/", "http://0127.0.0.1:8081/a")).isFalse();
    }

    @Test
    void plainHttpNeverReceivesWhatHttpsDoes() {
        assertThat(permitted("https://repo.example.com/", "http://repo.example.com/a")).isFalse();
        assertThat(permitted("https://repo.example.com/", "files.internal", "http://files.internal/a")).isFalse();
        assertThat(permitted("https://repo.example.com/", "files.internal:80", "http://files.internal/a")).isFalse();
        // an upstream that is itself plain HTTP (an internal mirror) has nothing to lose to a downgrade
        assertThat(permitted("http://mirror.internal/", "files.internal", "http://files.internal/a")).isTrue();
        // and a step up is fine for listed hosts
        assertThat(permitted("http://mirror.internal/", "files.internal", "https://files.internal/a")).isTrue();
        assertThat(permitted("http://mirror.internal/", "https://mirror.internal/a")).isFalse();
    }

    @Test
    void urlsWithoutAComparableOriginNeverReceiveTheCredentials() {
        assertThat(permitted("https://repo.example.com/", "ftp://repo.example.com/a")).isFalse();
        assertThat(permitted("https://repo.example.com/", "file:///etc/passwd")).isFalse();
        assertThat(UpstreamCredentials.permitted(proxy("https://repo.example.com/", null), URI.create("/relative/path"))).isFalse();
        assertThat(UpstreamCredentials.permitted(proxy("https://repo.example.com/", null), null)).isFalse();
        assertThat(UpstreamCredentials.permitted(proxy(null, null), URI.create("https://repo.example.com/"))).isFalse();
        assertThat(UpstreamCredentials.permitted(proxy("not a url", null), URI.create("https://repo.example.com/"))).isFalse();
        assertThat(permitted("https://repo.example.com/", "https://under_score.example.com/a")).isFalse();
    }

    @Test
    void listedHostsReceiveTheCredentialsOnTheirDefaultPortOrTheGivenOne() {
        String remote = "https://mirror.internal/";
        String hosts = " files.internal , downloads.internal:8443,[::1]:9000";
        assertThat(permitted(remote, hosts, "https://files.internal/a.zip")).isTrue();
        assertThat(permitted(remote, hosts, "https://FILES.internal./a.zip")).isTrue();
        assertThat(permitted(remote, hosts, "https://files.internal:8443/a.zip")).isFalse();
        assertThat(permitted(remote, hosts, "https://downloads.internal:8443/a.zip")).isTrue();
        assertThat(permitted(remote, hosts, "https://downloads.internal/a.zip")).isFalse();
        assertThat(permitted(remote, hosts, "https://[::1]:9000/a.zip")).isTrue();
        assertThat(permitted(remote, hosts, "https://sub.files.internal/a.zip")).isFalse();
        assertThat(permitted(remote, hosts, "https://github.com/a.zip")).isFalse();
    }

    @Test
    void aWildcardCoversEverySubdomainButNotTheDomainItself() {
        String remote = "https://mirror.internal/";
        assertThat(permitted(remote, "*.cdn.example.com", "https://eu.cdn.example.com/a")).isTrue();
        assertThat(permitted(remote, "*.cdn.example.com", "https://a.b.cdn.example.com/a")).isTrue();
        assertThat(permitted(remote, "*.cdn.example.com", "https://cdn.example.com/a")).isFalse();
        assertThat(permitted(remote, "*.cdn.example.com", "https://evilcdn.example.com/a")).isFalse();
        assertThat(permitted(remote, "*.cdn.example.com", "https://eu.cdn.example.com:8443/a")).isFalse();
        assertThat(permitted(remote, "*.cdn.example.com:8443", "https://eu.cdn.example.com:8443/a")).isTrue();
    }

    @Test
    void invalidStoredEntriesGrantNothingAndDoNotHideTheValidOnes() {
        Repository repo = proxy("https://mirror.internal/", "https://bad.example/, files.internal, *, ,a b");
        assertThat(UpstreamCredentials.trustedHosts(repo)).extracting(UpstreamCredentials.HostPattern::host)
                .containsExactly("files.internal");
        assertThat(UpstreamCredentials.permitted(repo, URI.create("https://bad.example/x"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://files.internal", "files.internal/path", "user@files.internal", "*", "*.com",
            "*.10.0.0.1", "files.*.internal", "files.internal:0", "files.internal:65536", "files.internal:http",
            "2001:db8::1", "[2001:db8::1", "[not-an-ip]", "[2001:db8::1]x", "files..internal", ".files.internal",
            "-files.internal", "under_score.internal", "files internal"})
    void validationRefusesAnythingThatIsNotAHostList(String entry) {
        Repository repo = proxy("https://mirror.internal/", "files.internal," + entry);
        assertThatThrownBy(() -> UpstreamCredentials.validate(repo))
                .isInstanceOfSatisfying(GranaryException.class, e -> {
                    assertThat(e.code()).isEqualTo("repository.upstream.credentialHosts.invalid");
                    assertThat(e.getMessage()).contains(UpstreamCredentials.HOSTS_SETTING).contains(entry.trim());
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"files.internal", "files.internal:8443", "FILES.Internal.", "*.cdn.example.com",
            "*.cdn.example.com:443", "10.0.0.5", "10.0.0.5:8080", "[::1]", "[2001:db8::1]:9000", "bücher.example",
            "localhost", "a.internal, b.internal ,c.internal:1", ""})
    void validationAcceptsHostLists(String value) {
        Repository repo = proxy("https://mirror.internal/", value);
        UpstreamCredentials.validate(repo);
    }

    @Test
    void namedHostsReceiveTheCredentialsOnlyOnTheirOwnOriginAndNeverOverPlainHttpFromHttps() {
        Repository repo = proxy("https://registry.example.com/", null);
        URI realm = URI.create("https://auth.example.com/token?scope=x");
        assertThat(UpstreamCredentials.permittedAtNamedHost(repo, realm, realm)).isTrue();
        assertThat(UpstreamCredentials.permittedAtNamedHost(repo, realm, URI.create("https://auth.example.com:443/other"))).isTrue();
        assertThat(UpstreamCredentials.permittedAtNamedHost(repo, realm, URI.create("https://evil.test/token"))).isFalse();
        assertThat(UpstreamCredentials.permittedAtNamedHost(repo, realm, URI.create("https://registry.example.com/token"))).isFalse();
        URI plain = URI.create("http://auth.example.com/token");
        assertThat(UpstreamCredentials.permittedAtNamedHost(repo, plain, plain)).isFalse();
        Repository local = proxy("http://localhost:5000/", null);
        URI localRealm = URI.create("http://localhost:5001/auth");
        assertThat(UpstreamCredentials.permittedAtNamedHost(local, localRealm, localRealm)).isTrue();
    }

    @Test
    void credentialsArePresentWithAUsernameOrAToken() {
        Repository repo = proxy("https://r.example/", null);
        assertThat(UpstreamCredentials.present(repo)).isTrue();
        repo.setRemoteUsername(" ");
        assertThat(UpstreamCredentials.present(repo)).isFalse();
        repo.setRemoteToken("t");
        assertThat(UpstreamCredentials.present(repo)).isTrue();
    }

    @Test
    void everyFormatThatProxiesOffersTheSettingAfterItsOwn() {
        SettingDescriptor own = SettingDescriptor.string("x.own", "Own", null, "Own setting.");
        FormatHandler proxying = handler("x", EnumSet.allOf(RepositoryType.class), List.of(own));
        FormatHandler hostedOnly = handler("y", EnumSet.of(RepositoryType.HOSTED), List.of(own));
        FormatRegistry registry = new FormatRegistry(List.of(proxying, hostedOnly));

        assertThat(registry.settings(proxying)).containsExactly(own, UpstreamCredentials.HOSTS_DESCRIPTOR);
        assertThat(registry.settings(hostedOnly)).containsExactly(own);
        assertThat(UpstreamCredentials.HOSTS_DESCRIPTOR.appliesTo()).containsExactly(RepositoryType.PROXY);
        assertThat(UpstreamCredentials.HOSTS_DESCRIPTOR.type()).isEqualTo(SettingDescriptor.Type.STRING);
        assertThat(UpstreamCredentials.HOSTS_DESCRIPTOR.description()).endsWith(".");
    }

    private static FormatHandler handler(String id, Set<RepositoryType> types, List<SettingDescriptor> settings) {
        return new FormatHandler() {
            @Override
            public String format() {
                return id;
            }

            @Override
            public String displayName() {
                return id;
            }

            @Override
            public Set<RepositoryType> supportedTypes() {
                return types;
            }

            @Override
            public List<SettingDescriptor> settings() {
                return settings;
            }

            @Override
            public ResponseEntity<?> handle(dev.granary.core.format.RepositoryRequest request) {
                return ResponseEntity.ok().build();
            }
        };
    }
}
