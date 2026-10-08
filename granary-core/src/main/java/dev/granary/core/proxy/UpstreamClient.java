package dev.granary.core.proxy;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Repository;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around the JDK {@link HttpClient} for talking to upstream registries. Honours the standard
 * {@code https.proxyHost} / {@code http.proxyHost} system properties, so corporate proxies work out of the box.
 */
@Component
public class UpstreamClient {

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

    /** GET of an absolute URL on the same upstream (NuGet service indexes hand out absolute resource URLs). */
    public Response getAbsolute(Repository repo, URI uri, Map<String, String> conditional, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(readTimeout(repo))
                .header("User-Agent", config.getUserAgent());
        if (!extraHeaders.containsKey("Accept")) {
            builder.header("Accept", "*/*");
        }
        authorise(repo, builder);
        conditional.forEach(builder::header);
        extraHeaders.forEach(builder::header);
        HttpResponse<InputStream> response = client(connectTimeout(repo))
                .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new Response(response);
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
        return clients.computeIfAbsent(connectTimeout, t -> HttpClient.newBuilder()
                .connectTimeout(t)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .build());
    }
}
