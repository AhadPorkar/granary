package dev.granary.format.rubygems;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A proxy of rubygems.org (or any server that speaks the same protocol). Every document is cached under the path
 * the client asked for: the compact index and the specs indexes as metadata (re-validated after the repository's
 * metadata max age, served stale while upstream is unreachable), {@code .gem} files and quick specs as immutable
 * artifacts. Cached gems are linked to components so they show up in the UI.
 *
 * <p>Upstream is asked without {@code Accept-Encoding}, so what is cached is always the plain representation.
 *
 * <p>rubygems.org keeps gems and quick specs in S3 behind its CDN, which answers {@code 403 AccessDenied}, not
 * {@code 404}, for a file that does not exist. {@link ProxyService} counts a 403 as an outage, so a few requests for
 * a gem that is not there would open the circuit for the whole proxy and turn every other miss into a 503. For
 * these files a 403 is therefore taken as a plain miss: the repository's health is restored and the miss is
 * remembered in the negative cache like a 404. Index documents keep the strict rule, so credentials an upstream
 * rejects still show up as an outage.
 */
@Component
public class RubygemsProxy {

    private static final Logger log = LoggerFactory.getLogger(RubygemsProxy.class);

    private final ProxyService proxy;
    private final StorageService storage;
    private final UpstreamHealth health;
    private final NegativeCache negativeCache;

    public RubygemsProxy(ProxyService proxy, StorageService storage, UpstreamHealth health, NegativeCache negativeCache) {
        this.proxy = proxy;
        this.storage = storage;
        this.health = health;
        this.negativeCache = negativeCache;
    }

    /** An index document ({@code versions}, {@code info/…}, the specs files, the JSON API). */
    public Optional<StoredContent> document(Repository repo, String path) {
        return proxy.fetch(repo, path, FetchOptions.index(), Map.of());
    }

    /** A {@code .gem} or quick spec; immutable once cached. */
    public Optional<StoredContent> file(Repository repo, String path) {
        int failuresBefore = health.status(repo).consecutiveFailures();
        Optional<StoredContent> content = proxy.fetch(repo, path, FetchOptions.artifact(), Map.of());
        if (content.isEmpty()) {
            deniedMeansMissing(repo, path, failuresBefore);
        }
        return content.map(c -> linkComponent(repo, c));
    }

    /** Turns the 403 upstream just answered for {@code path} (and nothing else) back into a remembered miss. */
    private void deniedMeansMissing(Repository repo, String path, int failuresBefore) {
        UpstreamHealth.Status status = health.status(repo);
        if (status.consecutiveFailures() != failuresBefore + 1 || !("HTTP 403 for " + path).equals(status.lastError())) {
            return;
        }
        log.debug("upstream of {} denies {}; treating it as missing", repo.getName(), path);
        health.recordSuccess(repo);
        negativeCache.put(repo.getId(), path, proxy.negativeTtl(repo));
    }

    /** Links a freshly cached file to its gem version, so the UI lists cached gems like pushed ones. */
    private StoredContent linkComponent(Repository repo, StoredContent content) {
        Asset asset = content.asset();
        if (asset.getComponentId() != null) {
            return content;
        }
        GemPath path = GemPath.parse(asset.getPath());
        if (path.fullName() == null) {
            return content;
        }
        Optional<GemNames.FullName> parsed = GemNames.parse(path.fullName());
        if (parsed.isEmpty()) {
            return content;
        }
        StorageService.ComponentCoordinates coordinates = new StorageService.ComponentCoordinates("",
                parsed.get().name(), GemNames.versionKey(parsed.get().version(), parsed.get().platform()));
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                asset.setComponentId(storage.ensureComponent(repo, coordinates).getId());
                return new StoredContent(storage.save(asset), content.blobStore());
            } catch (RuntimeException e) {
                // a concurrent download of the quick spec of the same version created the component first
                asset.setComponentId(null);
                log.debug("linking {} in {} to its component failed: {}", asset.getPath(), repo.getName(), e.getMessage());
            }
        }
        return content;
    }
}
