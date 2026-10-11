package dev.granary.server;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.BasicCredentials;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.UpstreamCredentials;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared pieces of the tests proving that a proxy's upstream credentials reach the origin of its remote URL and no
 * other host: the credentials, a proxy definition carrying them, and request patterns that check for them.
 */
public final class UpstreamCredentialFixtures {

    public static final String USER = "mirror";
    public static final String PASSWORD = "s3cret";

    private UpstreamCredentialFixtures() {
    }

    /**
     * A proxy definition with Basic credentials for the upstream and, when {@code trustedHosts} is not null, the
     * {@code upstream.credentialHosts} setting. Not saved yet, so callers can add format settings.
     */
    public static Repository proxyWithCredentials(String name, String format, String remoteUrl, String trustedHosts) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat(format);
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(remoteUrl);
        r.setRemoteUsername(USER);
        r.setRemotePassword(PASSWORD);
        Map<String, String> settings = new LinkedHashMap<>();
        if (trustedHosts != null) {
            settings.put(UpstreamCredentials.HOSTS_SETTING, trustedHosts);
        }
        r.setFormatSettings(settings);
        return r;
    }

    /** {@code localhost:<port>} of a WireMock server, as {@code upstream.credentialHosts} lists it. */
    public static String hostOf(WireMockServer server) {
        return "localhost:" + server.port();
    }

    /** The request carries the proxy's credentials. */
    public static RequestPatternBuilder withCredentials(RequestPatternBuilder pattern) {
        return pattern.withBasicAuth(new BasicCredentials(USER, PASSWORD));
    }

    /** The request carries no {@code Authorization} header at all. */
    public static RequestPatternBuilder withoutCredentials(RequestPatternBuilder pattern) {
        return pattern.withoutHeader("Authorization");
    }

    /** Requests to {@code server} that carry an {@code Authorization} header of any kind. */
    public static int authorisedRequests(WireMockServer server) {
        return server.findAll(WireMock.anyRequestedFor(WireMock.anyUrl()).withHeader("Authorization", WireMock.matching(".*")))
                .size();
    }
}
