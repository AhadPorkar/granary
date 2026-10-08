package dev.granary.server.api;

import dev.granary.core.dao.AssetDao;
import dev.granary.core.dao.ComponentDao;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.FormatRegistry;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.RepositoryService;
import dev.granary.core.repository.StandardRepositories;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.settings.SettingsService;
import dev.granary.core.storage.StorageService;
import dev.granary.server.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1")
public class RepositoryController {

    private static final String MANAGE = "hasAnyAuthority('system:admin','repositories:manage')";

    private final RepositoryService repositories;
    private final FormatRegistry formats;
    private final ProxyService proxy;
    private final SettingsService settings;
    private final ComponentDao components;
    private final AssetDao assets;
    private final StorageService storage;
    private final StandardRepositories standard;

    public RepositoryController(RepositoryService repositories, FormatRegistry formats, ProxyService proxy,
                                SettingsService settings, ComponentDao components, AssetDao assets, StorageService storage,
                                StandardRepositories standard) {
        this.standard = standard;
        this.repositories = repositories;
        this.formats = formats;
        this.proxy = proxy;
        this.settings = settings;
        this.components = components;
        this.assets = assets;
        this.storage = storage;
    }

    @GetMapping("/formats")
    public List<Dtos.FormatView> formats() {
        return formats.all().stream().map(f -> new Dtos.FormatView(f.format(), f.displayName())).toList();
    }

    /** Every repository the caller may read; admins and repository managers see all of them. */
    @GetMapping("/repositories")
    public List<Dtos.RepositoryView> list(HttpServletRequest http) {
        GranaryPrincipal principal = CurrentUser.principal();
        boolean manager = principal.has("repositories:manage");
        return repositories.list().stream()
                .filter(r -> manager || principal.canRead(r.getName()))
                .map(r -> view(r, http))
                .toList();
    }

    @GetMapping("/repositories/{name}")
    public Dtos.RepositoryView get(@PathVariable String name, HttpServletRequest http) {
        Repository repo = repositories.require(name);
        GranaryPrincipal principal = CurrentUser.principal();
        if (!principal.has("repositories:manage") && !principal.canRead(name)) {
            throw new dev.granary.core.GranaryException(403, "auth.forbidden", "Insufficient privileges");
        }
        return view(repo, http);
    }

    @PostMapping("/repositories")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.RepositoryView create(@Valid @RequestBody Dtos.RepositoryRequest request, HttpServletRequest http) {
        return view(repositories.create(request.toEntity()), http);
    }

    /** Creates the conventional proxy/hosted/group trio for every installed format; safe to repeat. */
    @PostMapping("/repositories/standard")
    @PreAuthorize(MANAGE)
    public Map<String, Object> createStandard() {
        List<String> created = standard.createMissing();
        return Map.of("created", created, "templates", standard.templates().stream().map(t -> t.name()).toList());
    }

    @PutMapping("/repositories/{name}")
    @PreAuthorize(MANAGE)
    public Dtos.RepositoryView update(@PathVariable String name, @Valid @RequestBody Dtos.RepositoryRequest request,
                                      HttpServletRequest http) {
        return view(repositories.update(name, request.toEntity()), http);
    }

    @DeleteMapping("/repositories/{name}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name) {
        repositories.delete(name);
    }

    @PostMapping("/repositories/{name}/offline")
    @PreAuthorize(MANAGE)
    public Dtos.RepositoryView offline(@PathVariable String name, @RequestBody Dtos.OfflineRequest request,
                                       HttpServletRequest http) {
        return view(repositories.setOffline(name, request.offline()), http);
    }

    /** Forgets remembered upstream 404s and circuit-breaker state, e.g. after the network is back. */
    @PostMapping("/repositories/{name}/invalidate")
    @PreAuthorize(MANAGE)
    public Dtos.RepositoryView invalidate(@PathVariable String name, HttpServletRequest http) {
        Repository repo = repositories.require(name);
        proxy.invalidate(repo);
        return view(repo, http);
    }

    @GetMapping("/repositories/{name}/components")
    public Dtos.Page<Dtos.ComponentView> componentsOf(@PathVariable String name,
                                                      @RequestParam(defaultValue = "") String q,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        Repository repo = requireReadable(name);
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 500));
        var result = q.isBlank()
                ? components.findByRepositoryIdOrderByNamespaceAscNameAscVersionAsc(repo.getId(), pageable)
                : components.search(repo.getId(), q.trim(), pageable);
        return new Dtos.Page<>(result.map(c -> Dtos.ComponentView.of(c, repo.getName())).toList(),
                result.getTotalElements(), result.getNumber(), result.getSize());
    }

    @GetMapping("/repositories/{name}/components/{id}/assets")
    public List<Dtos.AssetView> assetsOfComponent(@PathVariable String name, @PathVariable UUID id) {
        Repository repo = requireReadable(name);
        return assets.findByComponentId(id).stream()
                .filter(a -> a.getRepositoryId().equals(repo.getId()))
                .map(Dtos.AssetView::of).toList();
    }

    @DeleteMapping("/repositories/{name}/components/{id}")
    @PreAuthorize(MANAGE)
    public Map<String, Object> deleteComponent(@PathVariable String name, @PathVariable UUID id) {
        Repository repo = repositories.require(name);
        Component component = components.findById(id)
                .filter(c -> c.getRepositoryId().equals(repo.getId()))
                .orElseThrow(() -> dev.granary.core.GranaryException.notFound("component.notFound", "Component not found"));
        int deleted = storage.deleteComponent(component.getId());
        return Map.of("deletedAssets", deleted);
    }

    @GetMapping("/repositories/{name}/assets")
    public Dtos.Page<Dtos.AssetView> assetsOf(@PathVariable String name,
                                              @RequestParam(defaultValue = "") String prefix,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        Repository repo = requireReadable(name);
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 500));
        if (!prefix.isBlank()) {
            List<Asset> list = storage.list(repo, prefix.trim());
            int from = Math.min(pageable.getPageNumber() * pageable.getPageSize(), list.size());
            int to = Math.min(from + pageable.getPageSize(), list.size());
            return new Dtos.Page<>(list.subList(from, to).stream().map(Dtos.AssetView::of).toList(),
                    list.size(), pageable.getPageNumber(), pageable.getPageSize());
        }
        var result = assets.findByRepositoryIdOrderByPathAsc(repo.getId(), pageable);
        return new Dtos.Page<>(result.map(Dtos.AssetView::of).toList(), result.getTotalElements(),
                result.getNumber(), result.getSize());
    }

    @DeleteMapping("/repositories/{name}/assets/{id}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAsset(@PathVariable String name, @PathVariable UUID id) {
        Repository repo = repositories.require(name);
        Asset asset = assets.findById(id)
                .filter(a -> a.getRepositoryId().equals(repo.getId()))
                .orElseThrow(() -> dev.granary.core.GranaryException.notFound("asset.notFound", "Asset not found"));
        storage.delete(repo, asset.getPath());
    }

    private Repository requireReadable(String name) {
        Repository repo = repositories.require(name);
        GranaryPrincipal principal = CurrentUser.principal();
        if (!principal.has("repositories:manage") && !principal.canRead(name)) {
            throw new dev.granary.core.GranaryException(403, "auth.forbidden", "Insufficient privileges");
        }
        return repo;
    }

    private Dtos.RepositoryView view(Repository repo, HttpServletRequest http) {
        String root = settings.baseUrl().orElseGet(() ->
                ServletUriComponentsBuilder.fromContextPath(http).build().toUriString());
        Dtos.UpstreamView upstream = repo.isProxy() ? Dtos.UpstreamView.of(proxy.status(repo)) : null;
        FormatHandler handler = formats.find(repo.getFormat()).orElse(null);
        String url = root + "/repository/" + repo.getName() + (handler == null ? "" : "/");
        return Dtos.RepositoryView.of(repo, url, repositories.statistics(repo), upstream);
    }
}
