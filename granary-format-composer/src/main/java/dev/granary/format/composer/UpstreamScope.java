package dev.granary.format.composer;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.UpstreamCredentials;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Composer repositories hand out URLs on other hosts: Packagist's search and advisory APIs live on
 * {@code packagist.org} while its metadata comes from {@code repo.packagist.org}, and nearly every dist URL
 * points at GitHub, GitLab or Bitbucket. Like Composer itself, the proxy sends the repository's upstream
 * credentials only to the upstream's own origin (and to the hosts an administrator listed in
 * {@code upstream.credentialHosts}); {@link dev.granary.core.proxy.UpstreamClient} enforces that for every request.
 * This class keeps a separate circuit breaker per foreign host so that GitHub rate limiting a download does not
 * make the metadata upstream look down.
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
        Optional<UpstreamCredentials.Origin> remote = UpstreamCredentials.Origin.of(repo.getRemoteUrl());
        return remote.isPresent() && remote.equals(UpstreamCredentials.Origin.of(uri));
    }

    /**
     * The repository itself for its own origin, otherwise a stand-in whose identity is derived from the foreign
     * origin, so that the stand-in's failures open a circuit breaker of its own. The stand-in keeps the remote URL,
     * the credentials and the settings, so the client applies the same credential rule as for the repository. Only
     * for talking to the network; never store content under the stand-in.
     */
    public static Repository hostScope(Repository repo, URI uri) {
        if (sameOrigin(repo, uri)) {
            return repo;
        }
        String origin = origin(uri);
        UUID id = UUID.nameUUIDFromBytes(("composer-upstream|" + repo.getId() + "|" + origin).getBytes(StandardCharsets.UTF_8));
        return copy(repo, id, repo.getName() + " via " + origin);
    }

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
        copy.setRemoteUsername(repo.getRemoteUsername());
        copy.setRemotePassword(repo.getRemotePassword());
        copy.setRemoteToken(repo.getRemoteToken());
        copy.setConnectTimeoutSeconds(repo.getConnectTimeoutSeconds());
        copy.setReadTimeoutSeconds(repo.getReadTimeoutSeconds());
        copy.setMetadataMaxAgeMinutes(repo.getMetadataMaxAgeMinutes());
        copy.setNegativeCacheMinutes(repo.getNegativeCacheMinutes());
        copy.setOfflineMode(repo.isOfflineMode());
        copy.setFormatSettings(new LinkedHashMap<>(repo.getFormatSettings()));
        return copy;
    }
}
