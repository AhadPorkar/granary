package dev.granary.format.nuget;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the registration documents ({@code v3/registration/{id}/index.json} and the per-version leaves)
 * that {@code dotnet restore} resolves dependencies from.
 *
 * <p>Three sources feed the same document shape: a hosted repository's stored manifests, an upstream
 * registration index whose URLs are rewritten so the client keeps talking to this server, and the union
 * of a group's members. All of them end in {@link #assemble}, which always emits a single page with the
 * items inlined: the client then needs exactly one request per package instead of one per page, and
 * there is never a page URL that could be unavailable while the index is cached.
 */
public final class RegistrationBuilder {

    private static final String CATALOG_ROOT_TYPE = "catalog:CatalogRoot";
    private static final String PERMALINK_TYPE = "catalog:Permalink";
    private static final String PACKAGE_REGISTRATION_TYPE = "PackageRegistration";

    private final ObjectMapper json;

    public RegistrationBuilder(ObjectMapper json) {
        this.json = json;
    }

    // --- hosted ------------------------------------------------------------------------------

    /** The registration index of a hosted package: one page containing every stored version. */
    public ObjectNode index(String baseUrl, String id, Collection<PackageEntry> entries) {
        List<ObjectNode> items = new ArrayList<>(entries.size());
        for (PackageEntry entry : entries) {
            items.add(item(baseUrl, entry));
        }
        return assemble(baseUrl, id, items);
    }

    /** A page item ({@code "@type": "Package"}) for one stored version, catalog entry inlined. */
    public ObjectNode item(String baseUrl, PackageEntry entry) {
        String id = entry.lowerCaseId();
        String version = entry.version().toLowerCaseString();
        ObjectNode item = json.createObjectNode();
        item.put("@id", NuGetLayout.registrationLeafUrl(baseUrl, id, version));
        item.put("@type", "Package");
        item.set("catalogEntry", catalogEntry(baseUrl, entry));
        item.put("packageContent", NuGetLayout.packageContentUrl(baseUrl, id, version));
        item.put("registration", NuGetLayout.registrationIndexUrl(baseUrl, id));
        return item;
    }

    /** The {@code PackageDetails} object describing one version, derived from its manifest. */
    public ObjectNode catalogEntry(String baseUrl, PackageEntry entry) {
        Nuspec nuspec = entry.nuspec();
        String id = entry.lowerCaseId();
        String version = entry.version().toLowerCaseString();
        ObjectNode e = json.createObjectNode();
        e.put("@id", NuGetLayout.registrationLeafUrl(baseUrl, id, version));
        e.put("@type", "PackageDetails");
        e.put("authors", nuspec.authors() == null ? "" : nuspec.authors());
        putIfPresent(e, "copyright", nuspec.copyright());
        ArrayNode groups = e.putArray("dependencyGroups");
        for (Nuspec.DependencyGroup group : nuspec.dependencyGroups()) {
            ObjectNode g = groups.addObject();
            g.put("@type", "PackageDependencyGroup");
            if (group.targetFramework() != null) {
                g.put("targetFramework", group.targetFramework());
            }
            ArrayNode deps = g.putArray("dependencies");
            for (Nuspec.Dependency dependency : group.dependencies()) {
                ObjectNode d = deps.addObject();
                d.put("@type", "PackageDependency");
                d.put("id", dependency.id());
                d.put("range", rangeOf(dependency.versionRange()));
                d.put("registration", NuGetLayout.registrationIndexUrl(baseUrl, dependency.id()));
            }
        }
        e.put("description", nuspec.description() == null ? "" : nuspec.description());
        putIfPresent(e, "iconUrl", nuspec.iconUrl());
        e.put("id", nuspec.id());
        putIfPresent(e, "licenseExpression", nuspec.licenseExpression());
        putIfPresent(e, "licenseUrl", nuspec.licenseUrl());
        e.put("listed", entry.listed());
        e.put("packageContent", NuGetLayout.packageContentUrl(baseUrl, id, version));
        putIfPresent(e, "projectUrl", nuspec.projectUrl());
        e.put("published", entry.published().toString());
        putIfPresent(e, "releaseNotes", nuspec.releaseNotes());
        e.put("requireLicenseAcceptance", nuspec.requireLicenseAcceptance());
        putIfPresent(e, "summary", nuspec.summary());
        ArrayNode tags = e.putArray("tags");
        nuspec.tags().forEach(tags::add);
        putIfPresent(e, "title", nuspec.title());
        e.put("version", entry.version().toNormalizedString());
        return e;
    }

    // --- proxy -------------------------------------------------------------------------------

    /**
     * Rewrites an upstream registration index (pages already inlined) so that every {@code @id},
     * {@code registration} and {@code packageContent} points at this repository. Items whose version
     * cannot be determined are dropped rather than left pointing upstream.
     */
    public ObjectNode rewrite(JsonNode upstreamIndex, String baseUrl, String id) {
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode leaf : leaves(upstreamIndex)) {
            rewriteItem(leaf, baseUrl, id).ifPresent(items::add);
        }
        return assemble(baseUrl, id, items);
    }

    private Optional<ObjectNode> rewriteItem(JsonNode upstreamItem, String baseUrl, String id) {
        Optional<NuGetVersion> parsed = versionOf(upstreamItem);
        if (parsed.isEmpty() || !upstreamItem.isObject()) {
            return Optional.empty();
        }
        String version = parsed.get().toLowerCaseString();
        ObjectNode item = (ObjectNode) upstreamItem.deepCopy();
        item.put("@id", NuGetLayout.registrationLeafUrl(baseUrl, id, version));
        item.put("@type", "Package");
        item.put("packageContent", NuGetLayout.packageContentUrl(baseUrl, id, version));
        item.put("registration", NuGetLayout.registrationIndexUrl(baseUrl, id));
        JsonNode catalogEntry = item.path("catalogEntry");
        if (catalogEntry.isObject()) {
            ObjectNode e = (ObjectNode) catalogEntry;
            String leafUrl = NuGetLayout.registrationLeafUrl(baseUrl, id, version);
            e.put("packageContent", NuGetLayout.packageContentUrl(baseUrl, id, version));
            for (JsonNode group : e.path("dependencyGroups")) {
                for (JsonNode dependency : group.path("dependencies")) {
                    if (dependency.isObject() && dependency.has("registration") && dependency.path("id").isString()) {
                        ((ObjectNode) dependency).put("registration",
                                NuGetLayout.registrationIndexUrl(baseUrl, dependency.path("id").stringValue()));
                    }
                }
            }
            rewriteFragmentIds(e, leafUrl);
            e.put("@id", leafUrl);
        }
        return Optional.of(item);
    }

    /**
     * Catalog entries carry fragment ids for their parts ({@code …/catalog0/….json#dependencygroup/net6.0});
     * they are moved onto the leaf URL so no identifier in the document names the upstream catalog.
     */
    private static void rewriteFragmentIds(JsonNode node, String leafUrl) {
        if (node.isObject()) {
            JsonNode id = node.path("@id");
            if (id.isString() && id.stringValue().indexOf('#') > 0) {
                ((ObjectNode) node).put("@id", leafUrl + id.stringValue().substring(id.stringValue().indexOf('#')));
            }
            for (JsonNode child : node.values()) {
                rewriteFragmentIds(child, leafUrl);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                rewriteFragmentIds(child, leafUrl);
            }
        }
    }

    // --- group -------------------------------------------------------------------------------

    /**
     * Unions registration indexes that were already built for {@code baseUrl}: the first index to list a
     * version wins, mirroring how group lookups of the package file itself take the first member.
     */
    public ObjectNode merge(String baseUrl, String id, List<? extends JsonNode> indexes) {
        Map<NuGetVersion, ObjectNode> byVersion = new LinkedHashMap<>();
        for (JsonNode index : indexes) {
            for (JsonNode leaf : leaves(index)) {
                Optional<NuGetVersion> version = versionOf(leaf);
                if (version.isPresent() && leaf.isObject()) {
                    byVersion.putIfAbsent(version.get(), (ObjectNode) leaf.deepCopy());
                }
            }
        }
        return assemble(baseUrl, id, new ArrayList<>(byVersion.values()));
    }

    // --- shared ------------------------------------------------------------------------------

    /** Orders the items by version and wraps them in a single inlined page. */
    public ObjectNode assemble(String baseUrl, String id, List<ObjectNode> items) {
        List<ObjectNode> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> versionOf(a).orElseThrow().compareTo(versionOf(b).orElseThrow()));
        String indexUrl = NuGetLayout.registrationIndexUrl(baseUrl, id);
        ObjectNode root = json.createObjectNode();
        root.put("@id", indexUrl);
        ArrayNode types = root.putArray("@type");
        types.add(CATALOG_ROOT_TYPE);
        types.add(PACKAGE_REGISTRATION_TYPE);
        types.add(PERMALINK_TYPE);
        ArrayNode pages = root.putArray("items");
        if (!sorted.isEmpty()) {
            String lower = versionOf(sorted.get(0)).orElseThrow().toLowerCaseString();
            String upper = versionOf(sorted.get(sorted.size() - 1)).orElseThrow().toLowerCaseString();
            ObjectNode page = pages.addObject();
            page.put("@id", NuGetLayout.registrationPageUrl(baseUrl, id, lower, upper));
            page.put("@type", "catalog:CatalogPage");
            page.put("count", sorted.size());
            ArrayNode pageItems = page.putArray("items");
            sorted.forEach(pageItems::add);
            page.put("parent", indexUrl);
            page.put("lower", lower);
            page.put("upper", upper);
        }
        root.put("count", pages.size());
        root.set("@context", context());
        return root;
    }

    /** The leaf document of one version, extracted from an index built for the same {@code baseUrl}. */
    public Optional<ObjectNode> leaf(JsonNode index, String baseUrl, String id, NuGetVersion version) {
        for (JsonNode item : leaves(index)) {
            Optional<NuGetVersion> v = versionOf(item);
            if (v.isPresent() && v.get().equals(version)) {
                String leafUrl = NuGetLayout.registrationLeafUrl(baseUrl, id, version.toLowerCaseString());
                JsonNode entry = item.path("catalogEntry");
                ObjectNode leaf = json.createObjectNode();
                leaf.put("@id", leafUrl);
                ArrayNode types = leaf.putArray("@type");
                types.add("Package");
                types.add("http://schema.nuget.org/catalog#Permalink");
                leaf.put("catalogEntry", leafUrl);
                leaf.put("listed", entry.path("listed").asBoolean(true));
                leaf.put("packageContent", NuGetLayout.packageContentUrl(baseUrl, id, version.toLowerCaseString()));
                if (entry.path("published").isString()) {
                    leaf.put("published", entry.path("published").stringValue());
                }
                leaf.put("registration", NuGetLayout.registrationIndexUrl(baseUrl, id));
                return Optional.of(leaf);
            }
        }
        return Optional.empty();
    }

    /** All page items of an index, whether the pages are inlined or not (non-inlined pages contribute nothing). */
    public static List<JsonNode> leaves(JsonNode index) {
        List<JsonNode> result = new ArrayList<>();
        if (index == null) {
            return result;
        }
        for (JsonNode page : index.path("items")) {
            for (JsonNode item : page.path("items")) {
                result.add(item);
            }
        }
        return result;
    }

    /** The version of a page item, from its catalog entry or, failing that, from its {@code @id}. */
    public static Optional<NuGetVersion> versionOf(JsonNode item) {
        JsonNode version = item.path("catalogEntry").path("version");
        if (version.isString()) {
            Optional<NuGetVersion> parsed = NuGetVersion.tryParse(version.stringValue());
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        JsonNode id = item.path("@id");
        if (id.isString()) {
            String url = id.stringValue();
            int slash = url.lastIndexOf('/');
            String last = slash >= 0 ? url.substring(slash + 1) : url;
            if (last.endsWith(".json")) {
                return NuGetVersion.tryParse(last.substring(0, last.length() - ".json".length()));
            }
        }
        return Optional.empty();
    }

    /**
     * Turns a nuspec {@code version} attribute into the range notation registration documents use: a bare
     * version is a minimum ({@code 1.0} → {@code [1.0.0, )}), an explicit range is kept, nothing means
     * "any version".
     */
    public static String rangeOf(String nuspecVersion) {
        if (nuspecVersion == null || nuspecVersion.isBlank()) {
            return "(, )";
        }
        String v = nuspecVersion.trim();
        if (v.startsWith("[") || v.startsWith("(")) {
            return v;
        }
        return "[" + NuGetVersion.normalise(v) + ", )";
    }

    private ObjectNode context() {
        ObjectNode context = json.createObjectNode();
        context.put("@vocab", "http://schema.nuget.org/schema#");
        context.put("catalog", "http://schema.nuget.org/catalog#");
        context.put("xsd", "http://www.w3.org/2001/XMLSchema#");
        ObjectNode items = context.putObject("items");
        items.put("@id", "catalog:item");
        items.put("@container", "@set");
        context.putObject("count").put("@id", "catalog:count");
        ObjectNode parent = context.putObject("parent");
        parent.put("@id", "catalog:parent");
        parent.put("@type", "@id");
        ObjectNode tags = context.putObject("tags");
        tags.put("@container", "@set");
        tags.put("@id", "tag");
        context.putObject("registration").put("@type", "@id");
        context.putObject("packageContent").put("@type", "@id");
        context.putObject("catalogEntry").put("@type", "@id");
        return context;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
