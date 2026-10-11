package dev.granary.server.api;

import dev.granary.core.blob.BlobStore;
import dev.granary.core.blob.BlobStoreRegistry;
import dev.granary.core.dao.ComponentDao;
import dev.granary.core.format.FormatRegistry;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.RepositoryService;
import dev.granary.core.security.UserService;
import dev.granary.core.settings.SettingsService;
import dev.granary.core.storage.BlobCompactionService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.info.BuildProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SystemController {

    private final SettingsService settings;
    private final FormatRegistry formats;
    private final BlobStoreRegistry blobStores;
    private final RepositoryService repositories;
    private final ProxyService proxy;
    private final UserService users;
    private final ComponentDao components;
    private final BlobCompactionService compaction;
    private final String version;

    public SystemController(SettingsService settings, FormatRegistry formats, BlobStoreRegistry blobStores,
                            RepositoryService repositories, ProxyService proxy, UserService users,
                            ComponentDao components, BlobCompactionService compaction,
                            java.util.Optional<BuildProperties> build) {
        this.settings = settings;
        this.formats = formats;
        this.blobStores = blobStores;
        this.repositories = repositories;
        this.proxy = proxy;
        this.users = users;
        this.components = components;
        this.compaction = compaction;
        this.version = build.map(BuildProperties::getVersion).orElse("dev");
    }

    /** Public: what the login page needs to know. */
    @GetMapping("/system/info")
    public Map<String, Object> info() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "Granary");
        map.put("version", version);
        map.put("formats", formats.names());
        map.put("anonymousRead", settings.isAnonymousReadEnabled());
        map.put("offlineMode", settings.isGlobalOffline());
        return map;
    }

    @GetMapping("/system/status")
    @PreAuthorize("hasAnyAuthority('system:admin','settings:manage','repositories:manage')")
    public Map<String, Object> status() throws IOException {
        Map<String, Object> map = new LinkedHashMap<>(info());
        List<Map<String, Object>> stores = new ArrayList<>();
        for (BlobStore store : blobStores.all()) {
            BlobStore.BlobStoreStats stats = store.stats();
            stores.add(Map.of("name", store.name(), "blobCount", stats.blobCount(),
                    "totalBytes", stats.totalBytes(), "availableBytes", stats.availableBytes()));
        }
        map.put("blobStores", stores);
        List<Map<String, Object>> upstreams = new ArrayList<>();
        for (Repository repo : repositories.list()) {
            if (repo.isProxy()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("repository", repo.getName());
                entry.put("remoteUrl", repo.getRemoteUrl());
                entry.put("offlineMode", proxy.isOffline(repo));
                entry.put("status", Dtos.UpstreamView.of(proxy.status(repo)));
                upstreams.add(entry);
            }
        }
        map.put("upstreams", upstreams);
        map.put("repositories", repositories.list().size());
        map.put("settings", settings.snapshot());
        map.put("defaultAdminPassword", users.find(UserService.ADMIN_USERNAME)
                .map(admin -> users.passwordMatches(admin, "admin123")).orElse(false));
        Runtime rt = Runtime.getRuntime();
        map.put("jvm", Map.of("version", Runtime.version().toString(), "maxMemory", rt.maxMemory(),
                "totalMemory", rt.totalMemory(), "freeMemory", rt.freeMemory(), "processors", rt.availableProcessors()));
        return map;
    }

    @GetMapping("/system/settings")
    @PreAuthorize("hasAnyAuthority('system:admin','settings:manage')")
    public Map<String, Object> settings() {
        return settings.snapshot();
    }

    @PutMapping("/system/settings")
    @PreAuthorize("hasAnyAuthority('system:admin','settings:manage')")
    public Map<String, Object> updateSettings(@RequestBody Dtos.SettingsRequest request) {
        if (request.anonymousRead() != null) {
            settings.setAnonymousReadEnabled(request.anonymousRead());
        }
        if (request.offlineMode() != null) {
            settings.setGlobalOffline(request.offlineMode());
        }
        if (request.baseUrl() != null) {
            settings.setBaseUrl(request.baseUrl());
        }
        return settings.snapshot();
    }

    /**
     * Deletes the blobs of a store that no asset references any more and that are older than the grace period.
     * Same privileges as changing the global settings. 404 for an unknown store, 409 while it is being compacted.
     */
    @PostMapping("/blob-stores/{name}/compact")
    @PreAuthorize("hasAnyAuthority('system:admin','settings:manage')")
    public BlobCompactionService.Report compact(@PathVariable String name) {
        return compaction.compact(name);
    }

    /** Component search across every repository the caller may read. */
    @GetMapping("/search")
    public Dtos.Page<Dtos.ComponentView> search(@RequestParam(defaultValue = "") String q,
                                                @RequestParam(required = false) String repository,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        var principal = dev.granary.server.security.CurrentUser.principal();
        Map<java.util.UUID, String> readable = new LinkedHashMap<>();
        for (Repository repo : repositories.list()) {
            if ((repository == null || repository.equals(repo.getName()))
                    && (principal.has("repositories:manage") || principal.canRead(repo.getName()))) {
                readable.put(repo.getId(), repo.getName());
            }
        }
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 500));
        java.util.UUID only = readable.size() == 1 ? readable.keySet().iterator().next() : null;
        var result = components.search(only, q.trim(), pageable);
        List<Dtos.ComponentView> items = result.getContent().stream()
                .filter(c -> readable.containsKey(c.getRepositoryId()))
                .map(c -> Dtos.ComponentView.of(c, readable.get(c.getRepositoryId())))
                .toList();
        return new Dtos.Page<>(items, result.getTotalElements(), result.getNumber(), result.getSize());
    }
}
