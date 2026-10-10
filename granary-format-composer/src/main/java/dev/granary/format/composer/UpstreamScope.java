package dev.granary.format.composer;

import dev.granary.core.model.Repository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;

/**
 * Composer repositories hand out URLs on other hosts: Packagist's search and advisory APIs live on
 * {@code packagist.org} while its metadata comes from {@code repo.packagist.org}, and nearly every dist URL
 * points at GitHub, GitLab or Bitbucket. Like Composer itself, the proxy sends the repository's upstream
 * credentials only to the upstream's own origin, and it keeps a separate circuit breaker per foreign host so that
 * GitHub rate limiting a download does not make the metadata upstream look down.
 */
public final class UpstreamScope {

    private UpstreamScope() {
    }

    /** {@code scheme://host[:port]} of a URL, lower case, default ports omitted; {@code null} when it has no host. */
    public static String origin(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getScheme() == null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port < 0 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }

    public static String origin(String url) {
        try {
            return origin(URI.create(url.trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Whether {@code uri} is on the same origin as the repository's remote URL. */
    public static boolean sameOrigin(Repository repo, URI uri) {
        String remote = repo.getRemoteUrl() == null ? null : origin(repo.getRemoteUrl());
        String target = origin(uri);
        return remote != null && remote.equals(target);
    }

    /**
     * The repository itself for its own origin, otherwise a copy without upstream credentials that shares the
     * repository's identity (cache, negative cache and circuit breaker). For {@code ProxyService} fetches.
     */
    public static Repository credentialsFor(Repository repo, URI uri) {
        return sameOrigin(repo, uri) ? repo : copy(repo, repo.getId(), repo.getName());
    }

    /**
     * The repository itself for its own origin, otherwise a stand-in without credentials whose identity is derived
     * from the foreign origin, so that the stand-in's failures open a circuit breaker of its own. Only for talking to
     * the network; never store content under the stand-in.
     */
    public static Repository hostScope(Repository repo, URI uri) {
        if (sameOrigin(repo, uri)) {
            return repo;
        }
        String origin = origin(uri);
        UUID id = UUID.nameUUIDFromBytes(("composer-upstream|" + repo.getId() + "|" + origin).getBytes(StandardCharsets.UTF_8));
        return copy(repo, id, repo.getName() + " via " + origin);
    }

    /** A copy without upstream credentials. */
    private static Repository copy(Repository repo, UUID id, String name) {
        Repository copy = new Repository();
        copy.setId(id);
        copy.setName(name);
        copy.setFormat(repo.getFormat());
        copy.setType(repo.getType());
        copy.setOnline(repo.isOnline());
        copy.setBlobStore(repo.getBlobStore());
        copy.setDescription(repo.getDescription());
        copy.setAllowRedeploy(repo.isAllowRedeploy());
        copy.setVersionPolicy(repo.getVersionPolicy());
        copy.setRemoteUrl(repo.getRemoteUrl());
        copy.setConnectTimeoutSeconds(repo.getConnectTimeoutSeconds());
        copy.setReadTimeoutSeconds(repo.getReadTimeoutSeconds());
        copy.setMetadataMaxAgeMinutes(repo.getMetadataMaxAgeMinutes());
        copy.setNegativeCacheMinutes(repo.getNegativeCacheMinutes());
        copy.setOfflineMode(repo.isOfflineMode());
        copy.setFormatSettings(new LinkedHashMap<>(repo.getFormatSettings()));
        return copy;
    }
}
