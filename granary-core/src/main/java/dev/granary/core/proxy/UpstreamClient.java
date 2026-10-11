package dev.granary.core.proxy;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Repository;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around the JDK {@link HttpClient} for talking to upstream registries. Honours the standard
 * {@code https.proxyHost} / {@code http.proxyHost} system properties, so corporate proxies work out of the box.
 *
 * <p>Every request decides on its own whether it carries the repository's upstream credentials, by the rule of
 * {@link UpstreamCredentials}: only the origin of the remote URL (and the hosts an administrator listed in
 * {@value UpstreamCredentials#HOSTS_SETTING}) receive them, whatever URL an upstream document names. Redirects
 * are followed here rather than by the JDK client, which would repeat every header on any host: each hop is
 * authorised afresh, so a redirect to a download host on another origin goes without the credentials, and an
 * {@code Authorization} header the caller passed is only repeated within the origin it was sent to. Redirects
 * from HTTPS to plain HTTP are not followed at all.
 */
@Component
public class UpstreamClient {

    /** As many redirects as the JDK client follows by default. */
    static final int MAX_REDIRECTS = 5;

    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

    /** Which credentials a request carries; see the public methods. */
    private enum Credentials { UPSTREAM, NONE, NAMED_HOST }

    /** An upstream response. Close it when done; closing also closes the body stream. */
    public static final class Response implements AutoCloseable {
        private final HttpResponse<InputStream> response;

        Response(HttpResponse<InputStream> response) {
            this.response = response;
        }

        public int status() {
            return response.statusCode();
        }

        public InputStream body() {
            return response.body();
        }

        public Optional<String> header(String name) {
            return response.headers().firstValue(name);
        }

        public Optional<Long> contentLength() {
            return response.headers().firstValueAsLong("Content-Length").stream().boxed().findFirst();
        }

        /** The URL that answered, after any redirects. */
        public URI uri() {
            return response.uri();
        }

        @Override
        public void close() {
            try {
                response.body().close();
            } catch (IOException ignored) {
                // nothing sensible to do with a failed close of a response body
            }
        }
    }

    private final GranaryProperties.Proxy config;
    private final Map<Duration, HttpClient> clients = new ConcurrentHashMap<>();

    public UpstreamClient(GranaryProperties properties) {
        this.config = properties.getProxy();
    }

    /**
     * Performs a GET of {@code path} relative to the repository's remote URL.
     *
     * @param conditional optional {@code If-None-Match}/{@code If-Modified-Since} values from the cached copy
     */
    public Response get(Repository repo, String path, Map<String, String> conditional, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        return getAbsolute(repo, upstreamUri(repo, path), conditional, extraHeaders);
    }

    /** Resolves {@code path} against the repository's remote URL, the same way {@link #get} does. */
    public URI upstreamUri(Repository repo, String path) {
        return URI.create(UrlPaths.join(repo.getRemoteUrl().trim(), path));
    }

    public Response get(Repository repo, String path) throws IOException, InterruptedException {
        return get(repo, path, Map.of(), Map.of());
    }

    /**
     * GET of an absolute URL, which upstream documents hand out for their own host and for any other (NuGet service
     * indexes, Helm chart URLs, Terraform download URLs, ...). The repository's credentials go along only where
     * {@link UpstreamCredentials#permitted} allows, on the first request and on every redirect; an
     * {@code Authorization} header in {@code extraHeaders} replaces them.
     */
    public Response getAbsolute(Repository repo, URI uri, Map<String, String> conditional, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        return send(repo, uri, conditional, extraHeaders, Credentials.UPSTREAM);
    }

    /** GET without the repository's credentials, wherever {@code uri} points (an anonymous protocol probe). */
    public Response getAnonymous(Repository repo, URI uri, Map<String, String> conditional,
                                 Map<String, String> extraHeaders) throws IOException, InterruptedException {
        return send(repo, uri, conditional, extraHeaders, Credentials.NONE);
    }

    /**
     * GET that sends the repository's credentials to {@code uri}'s own origin even when that is not the upstream's.
     * Only for a host the upstream protocol itself names as the place to authenticate, such as the token realm a
     * container registry names in its {@code WWW-Authenticate} challenge; every call site says why. Redirects that
     * leave that origin go without them, and plain HTTP never gets them when the remote URL is HTTPS.
     */
    public Response getWithCredentials(Repository repo, URI uri, Map<String, String> conditional,
                                       Map<String, String> extraHeaders) throws IOException, InterruptedException {
        return send(repo, uri, conditional, extraHeaders, Credentials.NAMED_HOST);
    }

    public Duration connectTimeout(Repository repo) {
        return repo.getConnectTimeoutSeconds() != null
                ? Duration.ofSeconds(repo.getConnectTimeoutSeconds())
                : config.getConnectTimeout();
    }

    public Duration readTimeout(Repository repo) {
        return repo.getReadTimeoutSeconds() != null
                ? Duration.ofSeconds(repo.getReadTimeoutSeconds())
                : config.getReadTimeout();
    }

    private Response send(Repository repo, URI uri, Map<String, String> conditional, Map<String, String> extraHeaders,
                          Credentials credentials) throws IOException, InterruptedException {
        boolean callerAuthorization = extraHeaders.keySet().stream().anyMatch(UpstreamClient::isAuthorization);
        HttpClient client = client(connectTimeout(repo));
        URI target = uri;
        for (int redirects = 0; ; redirects++) {
            boolean firstOrigin = target == uri || UpstreamCredentials.sameOrigin(uri, target);
            HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                    .GET()
                    .timeout(readTimeout(repo))
                    .header("User-Agent", config.getUserAgent());
            if (extraHeaders.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Accept"))) {
                builder.header("Accept", "*/*");
            }
            if (!callerAuthorization && carriesCredentials(repo, credentials, uri, target)) {
                authorise(repo, builder);
            }
            conditional.forEach(builder::header);
            extraHeaders.forEach((name, value) -> {
                // a token the caller obtained for one origin stays there, like the repository's own credentials
                if (firstOrigin || !isAuthorization(name)) {
                    builder.header(name, value);
                }
            });
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            URI next = redirectTarget(target, response.statusCode(), response.headers().firstValue("Location"));
            if (next == null) {
                return new Response(response);
            }
            response.body().close();
            if (redirects >= MAX_REDIRECTS) {
                throw new IOException("too many redirects from " + uri);
            }
            target = next;
        }
    }

    private static boolean carriesCredentials(Repository repo, Credentials credentials, URI requested, URI target) {
        return switch (credentials) {
            case UPSTREAM -> UpstreamCredentials.permitted(repo, target);
            case NONE -> false;
            case NAMED_HOST -> UpstreamCredentials.permittedAtNamedHost(repo, requested, target);
        };
    }

    /**
     * Where a redirect response sends the client, or {@code null} when it is not one to follow: not a redirect
     * status, no usable {@code Location}, another scheme than http(s), or a step down from HTTPS to HTTP.
     */
    static URI redirectTarget(URI current, int status, Optional<String> location) {
        if (!REDIRECTS.contains(status)) {
            return null;
        }
        if (location.isEmpty() || location.get().isBlank()) {
            return null;
        }
        URI next;
        try {
            next = current.resolve(new URI(location.get().trim()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
        String scheme = next.getScheme() == null ? "" : next.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || next.getHost() == null) {
            return null;
        }
        if ("https".equalsIgnoreCase(current.getScheme()) && scheme.equals("http")) {
            return null;
        }
        return next;
    }

    private static boolean isAuthorization(String header) {
        return header.equalsIgnoreCase("Authorization");
    }

    private void authorise(Repository repo, HttpRequest.Builder builder) {
        if (repo.getRemoteToken() != null && !repo.getRemoteToken().isBlank()) {
            builder.header("Authorization", "Bearer " + repo.getRemoteToken().trim());
        } else if (repo.getRemoteUsername() != null && !repo.getRemoteUsername().isBlank()) {
            String raw = repo.getRemoteUsername() + ":" + (repo.getRemotePassword() == null ? "" : repo.getRemotePassword());
            builder.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private HttpClient client(Duration connectTimeout) {
        // redirects are followed by send(), which decides about the credentials of every hop
        return clients.computeIfAbsent(connectTimeout, t -> HttpClient.newBuilder()
                .connectTimeout(t)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.getDefault())
                .build());
    }
}
