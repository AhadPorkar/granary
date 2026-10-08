package dev.granary.format.nuget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ServiceIndexTest {

    private static final String BASE = "https://granary.example.com/repository/nuget-all";

    private final ObjectMapper json = JsonMapper.builder().build();

    @Test
    void listsEveryResourceTheClientNeeds() {
        ObjectNode index = ServiceIndex.build(json, BASE);

        assertThat(index.path("version").stringValue()).isEqualTo("3.0.0");
        Map<String, String> byType = new java.util.LinkedHashMap<>();
        for (JsonNode resource : index.path("resources")) {
            byType.put(resource.path("@type").stringValue(), resource.path("@id").stringValue());
            assertThat(resource.path("comment").stringValue()).isNotBlank();
        }
        assertThat(byType).containsEntry("PackageBaseAddress/3.0.0", BASE + "/v3/flatcontainer/")
                .containsEntry("RegistrationsBaseUrl", BASE + "/v3/registration/")
                .containsEntry("RegistrationsBaseUrl/3.6.0", BASE + "/v3/registration/")
                .containsEntry("SearchQueryService", BASE + "/v3/query")
                .containsEntry("SearchQueryService/3.5.0", BASE + "/v3/query")
                .containsEntry("PackagePublish/2.0.0", BASE + "/v2/package")
                .containsEntry("SymbolPackagePublish/4.9.0", BASE + "/v2/symbolpackage");
    }

    @Test
    void resourceTypesAreUnique() {
        ObjectNode index = ServiceIndex.build(json, BASE);
        List<String> types = new java.util.ArrayList<>();
        index.path("resources").forEach(r -> types.add(r.path("@type").stringValue()));
        assertThat(types).doesNotHaveDuplicates();
        assertThat(types.stream().collect(Collectors.toSet())).hasSize(types.size());
    }

    @Test
    void findsResourcesInOrderOfPreference() {
        JsonNode upstream = json.readTree("""
                {"version": "3.0.0", "resources": [
                  {"@id": "https://api.nuget.org/v3/registration5-semver1/", "@type": "RegistrationsBaseUrl"},
                  {"@id": "https://api.nuget.org/v3-flatcontainer/", "@type": "PackageBaseAddress/3.0.0"},
                  {"@id": "https://api.nuget.org/v3/registration5-gz-semver2/", "@type": "RegistrationsBaseUrl/3.6.0"},
                  {"@id": "https://azuresearch-usnc.nuget.org/query", "@type": ["SearchQueryService", "SearchQueryService/3.0.0-rc"]},
                  {"@id": "https://azuresearch-ussc.nuget.org/query", "@type": "SearchQueryService/3.5.0"}
                ]}""");

        assertThat(ServiceIndex.resource(upstream, ServiceIndex.REGISTRATION_TYPES))
                .contains("https://api.nuget.org/v3/registration5-gz-semver2/");
        assertThat(ServiceIndex.resource(upstream, List.of("RegistrationsBaseUrl")))
                .contains("https://api.nuget.org/v3/registration5-semver1/");
        assertThat(ServiceIndex.resource(upstream, List.of("PackageBaseAddress/3.0.0")))
                .contains("https://api.nuget.org/v3-flatcontainer/");
        assertThat(ServiceIndex.resource(upstream, List.of("SearchQueryService/3.0.0-rc")))
                .contains("https://azuresearch-usnc.nuget.org/query");
        assertThat(ServiceIndex.resource(upstream, ServiceIndex.SEARCH_TYPES))
                .contains("https://azuresearch-ussc.nuget.org/query");
        assertThat(ServiceIndex.resource(upstream, List.of("PackagePublish/2.0.0"))).isEmpty();
        assertThat(ServiceIndex.resource(json.readTree("{}"), ServiceIndex.SEARCH_TYPES)).isEmpty();
        assertThat(ServiceIndex.resource(null, ServiceIndex.SEARCH_TYPES)).isEmpty();
    }

    @Test
    void ownIndexResolvesWithTheSameLookup() {
        ObjectNode index = ServiceIndex.build(json, BASE);
        assertThat(ServiceIndex.resource(index, ServiceIndex.REGISTRATION_TYPES)).contains(BASE + "/v3/registration/");
        assertThat(ServiceIndex.resource(index, List.of(ServiceIndex.PACKAGE_BASE_ADDRESS))).contains(BASE + "/v3/flatcontainer/");
    }
}
