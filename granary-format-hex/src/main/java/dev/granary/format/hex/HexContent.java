package dev.granary.format.hex;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the Hex repository API for every repository type. Hosted repositories answer from their own storage,
 * proxies pass upstream's signed documents and tarballs through byte for byte (so {@code HEX_MIRROR} keeps
 * working with hex.pm's key), and groups decode their members' documents, merge them and sign the result with
 * Granary's key under the group's repository name.
 */
@Component
public class HexContent {

    static final String PUBLIC_KEY = "public_key";
    static final int GROUP_CACHE_SIZE = 512;
    private static final Logger log = LoggerFactory.getLogger(HexContent.class);

    private final StorageService storage;
    private final ProxyService proxy;
    private final GroupResolver groups;
    private final HexHosted hosted;
    private final HexSigningKey key;
    private final Map<String, Merged> groupCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Merged> eldest) {
            return size() > GROUP_CACHE_SIZE;
        }
    };
    private final Map<UUID, UpstreamKey> upstreamKeys = new ConcurrentHashMap<>();

    public HexContent(StorageService storage, ProxyService proxy, GroupResolver groups, HexHosted hosted, HexSigningKey key) {
        this.storage = storage;
        this.proxy = proxy;
        this.groups = groups;
        this.hosted = hosted;
        this.key = key;
    }

    /** A registry document: stored bytes for hosted and proxy repositories, freshly merged and signed bytes for groups. */
    public sealed interface Document permits Stored, Composed {
    }

    public record Stored(StoredContent content) implements Document {
    }

    public record Composed(byte[] bytes) implements Document {
    }

    private record Merged(String fingerprint, byte[] bytes) {
    }

    private record UpstreamKey(String sha256, PublicKey key) {
    }

    // --- registry documents ----------------------------------------------------------------------

    public Optional<Document> registryDocument(Repository repo, HexPath.Kind kind, String name) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.registryDocument(repo, kind, name).map(Stored::new);
            case PROXY -> proxyDocument(repo, kind, name).map(Stored::new);
            case GROUP -> groupDocument(repo, kind, name).map(Composed::new);
        };
    }

    private Optional<byte[]> groupDocument(Repository group, HexPath.Kind kind, String name) {
        List<Repository> members = groups.leafMembers(group);
        List<Repository> owners = new ArrayList<>();
        List<StoredContent> found = new ArrayList<>();
        StringBuilder fingerprint = new StringBuilder(HexSettings.repositoryName(group)).append('|').append(key.fingerprint())
                .append("|verify=").append(HexSettings.verifyUpstream(group));
        for (Repository member : members) {
            Optional<StoredContent> content = memberDocument(member, kind, name);
            fingerprint.append('|').append(member.getName()).append('=')
                    .append(content.map(c -> c.asset().getBlobSha256()).orElse("-"));
            if (content.isPresent()) {
                owners.add(member);
                found.add(content.get());
            }
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        String cacheKey = group.getId() + "|" + HexHosted.documentPath(kind, name);
        synchronized (groupCache) {
            Merged cached = groupCache.get(cacheKey);
            if (cached != null && cached.fingerprint().contentEquals(fingerprint)) {
                return Optional.of(cached.bytes());
            }
        }
        List<byte[]> payloads = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            payloads.add(memberPayload(group, owners.get(i), found.get(i)).orElse(null));
        }
        payloads.removeIf(p -> p == null);
        if (payloads.isEmpty()) {
            return Optional.empty();
        }
        String repository = HexSettings.repositoryName(group);
        byte[] payload;
        try {
            payload = switch (kind) {
                case NAMES -> HexRegistry.encodeNames(HexMerge.names(repository, decodeAll(payloads, HexRegistry::decodeNames)));
                case VERSIONS -> HexRegistry.encodeVersions(HexMerge.versions(repository,
                        decodeAll(payloads, HexRegistry::decodeVersions)));
                case PACKAGE -> HexRegistry.encodePackage(HexMerge.pkg(name, repository,
                        decodeAll(payloads, HexRegistry::decodePackage).stream().filter(p -> p.name().equals(name)).toList()));
                default -> throw new IllegalArgumentException(kind + " is not a registry document");
            };
        } catch (IllegalArgumentException e) {
            log.warn("could not merge {} for group {}: {}", HexHosted.documentPath(kind, name), group.getName(), e.getMessage());
            return Optional.empty();
        }
        byte[] packed = HexRegistry.pack(payload, key::sign);
        synchronized (groupCache) {
            groupCache.put(cacheKey, new Merged(fingerprint.toString(), packed));
        }
        return Optional.of(packed);
    }

    private static <T> List<T> decodeAll(List<byte[]> payloads, Function<byte[], T> decoder) {
        List<T> result = new ArrayList<>();
        for (byte[] payload : payloads) {
            try {
                result.add(decoder.apply(payload));
            } catch (IllegalArgumentException e) {
                log.warn("skipping an undecodable member registry document: {}", e.getMessage());
            }
        }
        return result;
    }

    private Optional<StoredContent> memberDocument(Repository member, HexPath.Kind kind, String name) {
        return switch (member.getType()) {
            case HOSTED -> hosted.registryDocument(member, kind, name);
            case PROXY -> proxyDocument(member, kind, name);
            case GROUP -> Optional.empty(); // leafMembers never returns groups
        };
    }

    /**
     * A proxy's registry document. The first time a proxy serves one, its upstream {@code public_key} is cached
     * as well: a group verifies the proxy's documents with that key, and without it a group could not use
     * documents the proxy cached once the upstream is gone (offline mode, an outage).
     */
    private Optional<StoredContent> proxyDocument(Repository proxyRepo, HexPath.Kind kind, String name) {
        Optional<StoredContent> document = proxy.fetch(proxyRepo, HexHosted.documentPath(kind, name), FetchOptions.index());
        if (document.isPresent() && !storage.exists(proxyRepo, PUBLIC_KEY)) {
            try {
                proxy.fetch(proxyRepo, PUBLIC_KEY, FetchOptions.index());
            } catch (RuntimeException e) {
                log.debug("could not cache the public_key of {}: {}", proxyRepo.getName(), e.getMessage());
            }
        }
        return document;
    }

    /**
     * The verified payload of a member's document. Hosted documents are Granary's own; a proxy's are checked
     * against that proxy's {@code public_key} unless the group turned verification off.
     */
    private Optional<byte[]> memberPayload(Repository group, Repository member, StoredContent content) {
        HexRegistry.Signed signed;
        try {
            signed = HexRegistry.decodeSigned(HexRegistry.gunzip(content.readAllBytes()));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("registry document {} of {} is unreadable: {}", content.asset().getPath(), member.getName(), e.getMessage());
            return Optional.empty();
        }
        if (member.isProxy() && HexSettings.verifyUpstream(group)) {
            Optional<PublicKey> upstream = upstreamKey(member);
            if (upstream.isEmpty()) {
                log.warn("cannot verify {} of {}: its public_key is not available; leaving it out of group {}",
                        content.asset().getPath(), member.getName(), group.getName());
                return Optional.empty();
            }
            if (!HexSigningKey.verify(signed.payload(), signed.signature(), upstream.get())) {
                log.warn("signature of {} from {} does not match its public_key; leaving it out of group {}",
                        content.asset().getPath(), member.getName(), group.getName());
                return Optional.empty();
            }
        } else if (member.isHosted() && !key.verify(signed.payload(), signed.signature())) {
            log.warn("hosted registry document {} of {} is not signed with the current key", content.asset().getPath(),
                    member.getName());
            return Optional.empty();
        }
        return Optional.of(signed.payload());
    }

    private Optional<PublicKey> upstreamKey(Repository proxyRepo) {
        Optional<StoredContent> pem = proxy.fetch(proxyRepo, PUBLIC_KEY, FetchOptions.index());
        if (pem.isEmpty()) {
            return Optional.empty();
        }
        String sha = pem.get().asset().getBlobSha256();
        UpstreamKey cached = upstreamKeys.get(proxyRepo.getId());
        if (cached != null && cached.sha256().equals(sha)) {
            return Optional.of(cached.key());
        }
        try {
            PublicKey parsed = HexSigningKey.parsePublicKey(pem.get().readUtf8());
            upstreamKeys.put(proxyRepo.getId(), new UpstreamKey(sha, parsed));
            return Optional.of(parsed);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("public_key of {} is not an RSA public key: {}", proxyRepo.getName(), e.getMessage());
            return Optional.empty();
        }
    }

    // --- tarballs and documentation --------------------------------------------------------------

    public Optional<StoredContent> tarball(Repository repo, String name, String version) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.tarball(repo, name, version);
            case PROXY -> proxyArtifact(repo, HexPath.tarballPath(name, version), name, version);
            case GROUP -> groupTarball(repo, name, version);
        };
    }

    /** The tarball from the member whose registry lists the version first, the member a merged document took it from. */
    private Optional<StoredContent> groupTarball(Repository group, String name, String version) {
        List<Repository> members = groups.leafMembers(group);
        for (Repository member : members) {
            if (member.isHosted()) {
                Optional<StoredContent> hit = hosted.tarball(member, name, version);
                if (hit.isPresent()) {
                    return hit;
                }
            } else if (member.isProxy() && proxyLists(member, name, version)) {
                Optional<StoredContent> hit = proxyArtifact(member, HexPath.tarballPath(name, version), name, version);
                if (hit.isPresent()) {
                    return hit;
                }
            }
        }
        for (Repository member : members) {
            if (member.isProxy()) {
                Optional<StoredContent> hit = proxyArtifact(member, HexPath.tarballPath(name, version), name, version);
                if (hit.isPresent()) {
                    return hit;
                }
            }
        }
        return Optional.empty();
    }

    private boolean proxyLists(Repository proxyRepo, String name, String version) {
        Optional<StoredContent> doc = proxy.fetch(proxyRepo, HexPath.packagePath(name), FetchOptions.index());
        if (doc.isEmpty()) {
            return false;
        }
        try {
            return HexRegistry.unpack(doc.get().readAllBytes(), HexRegistry::decodePackage).release(version).isPresent();
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    public Optional<StoredContent> docs(Repository repo, String name, String version) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.docs(repo, name, version);
            case PROXY -> proxyArtifact(repo, HexHosted.docsPath(name, version), null, null);
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<StoredContent> hit = docs(member, name, version);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    /** An immutable upstream file, cached once and linked to its component so the UI lists it. */
    private Optional<StoredContent> proxyArtifact(Repository repo, String path, String name, String version) {
        Optional<StoredContent> content = proxy.fetch(repo, path, FetchOptions.artifact());
        if (content.isPresent() && name != null && content.get().asset().getComponentId() == null) {
            try {
                Asset asset = content.get().asset();
                asset.setComponentId(storage.ensureComponent(repo, HexHosted.coordinates(name, version)).getId());
                return Optional.of(new StoredContent(storage.save(asset), content.get().blobStore()));
            } catch (RuntimeException e) {
                log.warn("could not link {} of {} to its component: {}", path, repo.getName(), e.getMessage());
            }
        }
        return content;
    }

    // --- public key and client installs ----------------------------------------------------------

    /** The upstream public key of a proxy (hosted repositories and groups serve Granary's own). */
    public Optional<StoredContent> proxyPublicKey(Repository repo) {
        return proxy.fetch(repo, PUBLIC_KEY, FetchOptions.index());
    }

    /** {@code installs/…} from a proxy, or the first member of a group that has it. */
    public Optional<StoredContent> installs(Repository repo, HexPath path) {
        return switch (repo.getType()) {
            case HOSTED -> Optional.empty();
            case PROXY -> proxy.fetch(repo, path.storagePath(), path.isMetadata() ? FetchOptions.index() : FetchOptions.artifact());
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<StoredContent> hit = installs(member, path);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }
}
