package dev.granary.format.nuget;

import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The V3 service index ({@code index.json}): the one URL a client is configured with, from which it
 * discovers every other endpoint by resource type.
 *
 * <p>It is generated on every request rather than stored because it only depends on the repository's
 * external base URL, which can change when the server moves behind a reverse proxy; a cached copy
 * would then send clients to the wrong host.
 */
public final class ServiceIndex {

    public static final String VERSION = "3.0.0";

    public static final String PACKAGE_BASE_ADDRESS = "PackageBaseAddress/3.0.0";
    public static final String REGISTRATIONS_BASE_URL = "RegistrationsBaseUrl";
    public static final String REGISTRATIONS_BASE_URL_340 = "RegistrationsBaseUrl/3.4.0";
    public static final String REGISTRATIONS_BASE_URL_360 = "RegistrationsBaseUrl/3.6.0";
    public static final String SEARCH_QUERY_SERVICE = "SearchQueryService";
    public static final String SEARCH_QUERY_SERVICE_350 = "SearchQueryService/3.5.0";
    public static final String PACKAGE_PUBLISH = "PackagePublish/2.0.0";
    public static final String SYMBOL_PACKAGE_PUBLISH = "SymbolPackagePublish/4.9.0";

    /** Registration resource types in order of preference when reading an upstream service index. */
    public static final List<String> REGISTRATION_TYPES = List.of(
            REGISTRATIONS_BASE_URL_360, REGISTRATIONS_BASE_URL_340, "RegistrationsBaseUrl/3.0.0-rc",
            "RegistrationsBaseUrl/3.0.0-beta", REGISTRATIONS_BASE_URL);

    /** Search resource types in order of preference when reading an upstream service index. */
    public static final List<String> SEARCH_TYPES = List.of(
            SEARCH_QUERY_SERVICE_350, "SearchQueryService/3.0.0-rc", "SearchQueryService/3.0.0-beta", SEARCH_QUERY_SERVICE);

    private ServiceIndex() {
    }

    public static ObjectNode build(ObjectMapper json, String baseUrl) {
        ObjectNode root = json.createObjectNode();
        root.put("version", VERSION);
        ArrayNode resources = root.putArray("resources");
        resource(resources, NuGetLayout.flatContainerBase(baseUrl), PACKAGE_BASE_ADDRESS,
                "Base URL of where NuGet packages are stored, in the format "
                        + "https://host/v3/flatcontainer/{id-lower}/{version-lower}/{id-lower}.{version-lower}.nupkg");
        resource(resources, NuGetLayout.registrationBase(baseUrl), REGISTRATIONS_BASE_URL,
                "Base URL of package registration info");
        resource(resources, NuGetLayout.registrationBase(baseUrl), REGISTRATIONS_BASE_URL_340,
                "Base URL of package registration info, including semVerLevel 1 packages");
        resource(resources, NuGetLayout.registrationBase(baseUrl), REGISTRATIONS_BASE_URL_360,
                "Base URL of package registration info, including SemVer 2.0.0 packages");
        resource(resources, NuGetLayout.searchUrl(baseUrl), SEARCH_QUERY_SERVICE,
                "Query endpoint of NuGet search service");
        resource(resources, NuGetLayout.searchUrl(baseUrl), SEARCH_QUERY_SERVICE_350,
                "Query endpoint of NuGet search service, including SemVer 2.0.0 packages");
        resource(resources, NuGetLayout.publishUrl(baseUrl), PACKAGE_PUBLISH,
                "Endpoint for pushing and deleting packages (hosted repositories only)");
        resource(resources, NuGetLayout.symbolPublishUrl(baseUrl), SYMBOL_PACKAGE_PUBLISH,
                "Endpoint for pushing symbol packages (hosted repositories only)");
        return root;
    }

    private static void resource(ArrayNode resources, String id, String type, String comment) {
        ObjectNode r = resources.addObject();
        r.put("@id", id);
        r.put("@type", type);
        r.put("comment", comment);
    }

    /**
     * Finds the URL of the first resource whose {@code @type} matches one of {@code types}, trying them in
     * the given order of preference. {@code @type} may be a string or an array of strings.
     */
    public static Optional<String> resource(JsonNode serviceIndex, List<String> types) {
        JsonNode resources = serviceIndex == null ? null : serviceIndex.path("resources");
        if (resources == null || !resources.isArray()) {
            return Optional.empty();
        }
        for (String wanted : types) {
            for (JsonNode resource : resources) {
                if (hasType(resource, wanted) && resource.path("@id").isString()) {
                    String url = resource.path("@id").stringValue();
                    if (url != null && !url.isBlank()) {
                        return Optional.of(url.trim());
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean hasType(JsonNode resource, String wanted) {
        JsonNode type = resource.path("@type");
        if (type.isString()) {
            return wanted.equals(type.stringValue());
        }
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (t.isString() && wanted.equals(t.stringValue())) {
                    return true;
                }
            }
        }
        return false;
    }
}
