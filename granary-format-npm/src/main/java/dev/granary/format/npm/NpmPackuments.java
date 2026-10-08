package dev.granary.format.npm;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes packuments and tarballs for every repository type, hiding where they come from:
 * hosted repositories keep them in storage, proxies cache them from upstream, groups merge their members.
 *
 * <p>Packuments are stored under the package name ({@code lodash}, {@code @babel/core}) and tarballs
 * under {@code {name}/-/{file}}, mirroring the registry URL layout so the asset browser reads naturally.
 */
@Component
public class NpmPackuments {

    public static final String JSON = "application/json";
    private static final Logger log = LoggerFactory.getLogger(NpmPackuments.class);
    private static final Map<String, String> ACCEPT_JSON = Map.of("Accept", JSON);

    private final StorageService storage;
    private final ProxyService proxy;
    private final UpstreamClient upstream;
    private final GroupResolver groups;
    private final ObjectMapper mapper;

    public NpmPackuments(StorageService storage, ProxyService proxy, UpstreamClient upstream, GroupResolver groups,
                         ObjectMapper mapper) {
        this.storage = storage;
        this.proxy = proxy;
        this.upstream = upstream;
        this.groups = groups;
        this.mapper = mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    /** The packument of {@code name} as the repository knows it, tarball URLs still pointing wherever they came from. */
    public Optional<ObjectNode> load(Repository repo, String name) {
        return switch (repo.getType()) {
            case HOSTED -> stored(repo, name);
            case PROXY -> proxy.fetch(repo, name, FetchOptions.index(), ACCEPT_JSON).flatMap(this::parse);
            case GROUP -> {
                List<ObjectNode> docs = new ArrayList<>();
                for (Repository member : groups.leafMembers(repo)) {
                    load(member, name).ifPresent(docs::add);
                }
                yield docs.isEmpty() ? Optional.empty() : Optional.of(NpmPackument.merge(docs));
            }
        };
    }

    /** The document in local storage, never touching upstream (what a hosted repository has, or a proxy has cached). */
    public Optional<ObjectNode> stored(Repository repo, String name) {
        return storage.find(repo, name).flatMap(this::parse);
    }

    public StoredContent store(Repository repo, ObjectNode doc) {
        String name = NpmPackument.string(doc, NpmPackument.NAME);
        byte[] bytes = toBytes(doc);
        return storage.store(repo, name, new ByteArrayInputStream(bytes), JSON, Asset.Origin.UPLOADED, true);
    }

    /** A tarball from wherever the repository type gets it; a group asks its members in order. */
    public Optional<StoredContent> tarball(Repository repo, NpmPath path) {
        return switch (repo.getType()) {
            case HOSTED -> storage.find(repo, path.tarballPath());
            case PROXY -> {
                Optional<StoredContent> cached = storage.find(repo, path.tarballPath());
                if (cached.isPresent()) {
                    yield cached;
                }
                yield proxy.fetch(repo, path.tarballPath(), upstreamTarballUri(repo, path), FetchOptions.artifact(), Map.of());
            }
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<StoredContent> hit = tarball(member, path);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    /**
     * Where upstream keeps a tarball. Normally {@code {remote}/{name}/-/{file}}, but some registries
     * (GitHub Packages, for one) hand out other shapes in {@code dist.tarball}, so the cached packument is
     * consulted first. Only URLs on the upstream's own host are followed, because the request carries the
     * repository's upstream credentials.
     */
    URI upstreamTarballUri(Repository repo, NpmPath path) {
        URI conventional = upstream.upstreamUri(repo, path.tarballPath());
        Optional<ObjectNode> doc = stored(repo, path.packageName());
        if (doc.isEmpty()) {
            return conventional;
        }
        String remoteHost = hostOf(repo.getRemoteUrl());
        for (Map.Entry<String, JsonNode> e : NpmPackument.entries(doc.get(), NpmPackument.VERSIONS)) {
            String url = NpmPackument.string(e.getValue().path(NpmPackument.DIST), NpmPackument.TARBALL);
            if (url == null || !path.fileName().equals(NpmPackument.fileNameOf(url))) {
                continue;
            }
            try {
                URI candidate = new URI(url);
                if (candidate.getHost() != null && candidate.getHost().equalsIgnoreCase(remoteHost)) {
                    return candidate;
                }
            } catch (URISyntaxException ignored) {
                // an unparsable upstream URL: fall back to the conventional location
            }
            break;
        }
        return conventional;
    }

    public Optional<ObjectNode> parse(StoredContent content) {
        try {
            JsonNode node = mapper.readTree(content.readAllBytes());
            if (node != null && node.isObject()) {
                return Optional.of((ObjectNode) node);
            }
            log.warn("stored packument {} is not a JSON object", content.asset().getPath());
            return Optional.empty();
        } catch (IOException | JacksonException e) {
            log.warn("stored packument {} is unreadable: {}", content.asset().getPath(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Parses a client-supplied JSON object, answering 400 for anything else. */
    public ObjectNode read(byte[] body) {
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw NpmError.badRequest("request body must be a JSON object");
            }
            return (ObjectNode) node;
        } catch (JacksonException e) {
            throw NpmError.badRequest("malformed JSON: " + e.getOriginalMessage());
        }
    }

    public byte[] toBytes(JsonNode node) {
        return mapper.writeValueAsBytes(node);
    }

    private static String hostOf(String url) {
        try {
            return url == null ? null : new URI(url.trim()).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
