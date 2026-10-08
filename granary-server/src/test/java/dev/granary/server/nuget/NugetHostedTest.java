package dev.granary.server.nuget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.server.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** The hosted NuGet flow end to end: push with an API key, then everything {@code dotnet restore} asks for. */
class NugetHostedTest extends IntegrationTest {

    private static final String REPO = "nuget-hosted";
    private static final String BASE = "http://localhost/repository/" + REPO;

    private String apiKey;

    @BeforeEach
    void setUp() {
        hosted(REPO, "nuget");
        apiKey = tokens.issue(users.require("admin"), "nuget-" + UUID.randomUUID(), null).secret();
    }

    private ResultActions push(byte[] nupkg, String key) throws Exception {
        var request = multipart(HttpMethod.PUT, "/repository/" + REPO + "/v2/package/")
                .file(new MockMultipartFile("package", "package.nupkg", "application/octet-stream", nupkg));
        if (key != null) {
            request.header("X-NuGet-ApiKey", key);
        }
        return mvc.perform(request);
    }

    @Test
    void pushThenRestoreRoundTrip() throws Exception {
        byte[] nupkg = NugetTestPackages.nupkg("Acme.Lib", "1.0.0", "Acme library", "Newtonsoft.Json", "13.0.1");
        push(nupkg, apiKey).andExpect(status().isCreated());

        mvc.perform(get("/repository/" + REPO + "/index.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.version").value("3.0.0"))
                .andExpect(jsonPath("$.resources[?(@['@type']=='PackageBaseAddress/3.0.0')]['@id']").value(BASE + "/v3/flatcontainer/"))
                .andExpect(jsonPath("$.resources[?(@['@type']=='RegistrationsBaseUrl/3.6.0')]['@id']").value(BASE + "/v3/registration/"))
                .andExpect(jsonPath("$.resources[?(@['@type']=='SearchQueryService')]['@id']").value(BASE + "/v3/query"))
                .andExpect(jsonPath("$.resources[?(@['@type']=='PackagePublish/2.0.0')]['@id']").value(BASE + "/v2/package"));

        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/acme.lib/index.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions").value(org.hamcrest.Matchers.contains("1.0.0")));

        MvcResult download = mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/acme.lib/1.0.0/acme.lib.1.0.0.nupkg"))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(nupkg);
        String etag = download.getResponse().getHeader("ETag");
        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/acme.lib/1.0.0/acme.lib.1.0.0.nupkg").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
        mvc.perform(head("/repository/" + REPO + "/v3/flatcontainer/acme.lib/1.0.0/acme.lib.1.0.0.nupkg"))
                .andExpect(status().isOk());

        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/acme.lib/1.0.0/acme.lib.nuspec"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<id>Acme.Lib</id>")));

        mvc.perform(get("/repository/" + REPO + "/v3/registration/acme.lib/index.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['@id']").value(BASE + "/v3/registration/acme.lib/index.json"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].lower").value("1.0.0"))
                .andExpect(jsonPath("$.items[0].upper").value("1.0.0"))
                .andExpect(jsonPath("$.items[0].items[0]['@id']").value(BASE + "/v3/registration/acme.lib/1.0.0.json"))
                .andExpect(jsonPath("$.items[0].items[0].packageContent").value(BASE + "/v3/flatcontainer/acme.lib/1.0.0/acme.lib.1.0.0.nupkg"))
                .andExpect(jsonPath("$.items[0].items[0].catalogEntry.id").value("Acme.Lib"))
                .andExpect(jsonPath("$.items[0].items[0].catalogEntry.version").value("1.0.0"))
                .andExpect(jsonPath("$.items[0].items[0].catalogEntry.description").value("Acme library"))
                .andExpect(jsonPath("$.items[0].items[0].catalogEntry.authors").value("Granary Tests"))
                .andExpect(jsonPath("$.items[0].items[0].catalogEntry.listed").value(true))
                .andExpect(jsonPath("$.items[0].items[0].catalogEntry.published").exists())
                .andExpect(jsonPath("$.items[0].items[0].catalogEntry.projectUrl").value("https://example.com/Acme.Lib"))
                .andExpect(jsonPath("$.items[0].items[0].catalogEntry.dependencyGroups[0].targetFramework").value("net8.0"))
                .andExpect(jsonPath("$.items[0].items[0].catalogEntry.dependencyGroups[0].dependencies[0].id").value("Newtonsoft.Json"))
                .andExpect(jsonPath("$.items[0].items[0].catalogEntry.dependencyGroups[0].dependencies[0].range").value("[13.0.1, )"));

        mvc.perform(get("/repository/" + REPO + "/v3/registration/acme.lib/1.0.0.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['@id']").value(BASE + "/v3/registration/acme.lib/1.0.0.json"))
                .andExpect(jsonPath("$.registration").value(BASE + "/v3/registration/acme.lib/index.json"))
                .andExpect(jsonPath("$.packageContent").value(BASE + "/v3/flatcontainer/acme.lib/1.0.0/acme.lib.1.0.0.nupkg"));

        mvc.perform(get("/repository/" + REPO + "/v3/query").param("q", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(1))
                .andExpect(jsonPath("$.data[0].id").value("Acme.Lib"))
                .andExpect(jsonPath("$.data[0].version").value("1.0.0"))
                .andExpect(jsonPath("$.data[0].registration").value(BASE + "/v3/registration/acme.lib/index.json"))
                .andExpect(jsonPath("$.data[0].versions[0]['@id']").value(BASE + "/v3/registration/acme.lib/1.0.0.json"))
                .andExpect(jsonPath("$.data[0].totalDownloads").value(1));
    }

    @Test
    void pushingTheSameVersionAgainIsAConflict() throws Exception {
        byte[] nupkg = NugetTestPackages.nupkg("Acme.Lib", "1.0.0", "first");
        push(nupkg, apiKey).andExpect(status().isCreated());
        push(NugetTestPackages.nupkg("acme.lib", "1.0", "same version, different spelling"), apiKey)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("repository.nuget.versionExists"));
        // the original is untouched
        mvc.perform(get("/repository/" + REPO + "/v3/registration/acme.lib/index.json"))
                .andExpect(jsonPath("$.items[0].items[0].catalogEntry.description").value("first"));
    }

    @Test
    void redeployIsAllowedWhenTheRepositorySaysSo() throws Exception {
        Repository r = new Repository();
        r.setName("nuget-redeploy");
        r.setFormat("nuget");
        r.setType(RepositoryType.HOSTED);
        r.setAllowRedeploy(true);
        repositories.create(r);

        for (String description : new String[] {"first", "second"}) {
            mvc.perform(multipart(HttpMethod.PUT, "/repository/nuget-redeploy/v2/package")
                            .file(new MockMultipartFile("package", "x.nupkg", null, NugetTestPackages.nupkg("Acme.Lib", "1.0.0", description)))
                            .header("X-NuGet-ApiKey", apiKey))
                    .andExpect(status().isCreated());
        }
        mvc.perform(get("/repository/nuget-redeploy/v3/registration/acme.lib/index.json"))
                .andExpect(jsonPath("$.items[0].items[0].catalogEntry.description").value("second"));
    }

    @Test
    void anonymousAndWrongCredentialsCannotPush() throws Exception {
        byte[] nupkg = NugetTestPackages.nupkg("Acme.Lib", "1.0.0", "x");
        push(nupkg, null).andExpect(status().isUnauthorized());
        push(nupkg, "gry_not_a_real_token").andExpect(status().isUnauthorized());
        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/acme.lib/index.json")).andExpect(status().isNotFound());
    }

    @Test
    void basicAuthWithTheTokenAsPasswordAlsoPushes() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/repository/" + REPO + "/v2/package")
                        .file(new MockMultipartFile("package", "x.nupkg", null, NugetTestPackages.nupkg("Acme.Lib", "1.0.0", "x")))
                        .header("Authorization", basic("admin", apiKey)))
                .andExpect(status().isCreated());
        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/acme.lib/index.json")).andExpect(status().isOk());
    }

    @Test
    void rawBodyPushIsAcceptedToo() throws Exception {
        mvc.perform(put("/repository/" + REPO + "/v2/package")
                        .contentType("application/octet-stream")
                        .content(NugetTestPackages.nupkg("Raw.Pkg", "0.1.0-alpha", "raw"))
                        .header("X-NuGet-ApiKey", apiKey))
                .andExpect(status().isCreated());
        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/raw.pkg/index.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0]").value("0.1.0-alpha"));
    }

    @Test
    void versionsAreNormalisedOnPushAndOnLookup() throws Exception {
        push(NugetTestPackages.nupkg("Acme.Lib", "1.0.01.0", "odd spelling"), apiKey).andExpect(status().isCreated());

        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/ACME.LIB/index.json"))
                .andExpect(jsonPath("$.versions[0]").value("1.0.1"));
        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/acme.lib/1.0.1/acme.lib.1.0.1.nupkg")).andExpect(status().isOk());
        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/Acme.Lib/1.0.01/Acme.Lib.1.0.01.nupkg")).andExpect(status().isOk());
        mvc.perform(get("/repository/" + REPO + "/v3/registration/acme.lib/1.0.1.0.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['@id']").value(BASE + "/v3/registration/acme.lib/1.0.1.json"));
    }

    @Test
    void invalidUploadsAreBadRequests() throws Exception {
        push("this is not a zip".getBytes(), apiKey)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.nuget.invalidPackage"));
        push(NugetTestPackages.zip(null, null, null), apiKey)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("no .nuspec")));
        push(NugetTestPackages.zip("Bad.nuspec", NugetTestPackages.nuspec("Bad", "one.two", "x"), null), apiKey)
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(HttpMethod.PUT, "/repository/" + REPO + "/v2/package").header("X-NuGet-ApiKey", apiKey))
                .andExpect(status().isBadRequest());
    }

    @Test
    void symbolPackagesAreStoredNextToThePackage() throws Exception {
        push(NugetTestPackages.nupkg("Acme.Lib", "1.0.0", "lib"), apiKey).andExpect(status().isCreated());
        push(NugetTestPackages.snupkg("Acme.Lib", "1.0.0"), apiKey).andExpect(status().isCreated());
        // pushing symbols again never fails, and the symbol publish endpoint works the same
        push(NugetTestPackages.snupkg("Acme.Lib", "1.0.0"), apiKey).andExpect(status().isCreated());
        mvc.perform(multipart(HttpMethod.PUT, "/repository/" + REPO + "/v2/symbolpackage/")
                        .file(new MockMultipartFile("package", "Acme.Lib.1.0.0.snupkg", null, NugetTestPackages.snupkg("Acme.Lib", "1.0.0")))
                        .header("X-NuGet-ApiKey", apiKey))
                .andExpect(status().isCreated());

        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/acme.lib/1.0.0/acme.lib.1.0.0.snupkg")).andExpect(status().isOk());
        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/acme.lib/index.json"))
                .andExpect(jsonPath("$.versions", hasSize(1)));
        mvc.perform(get("/repository/" + REPO + "/v3/registration/acme.lib/index.json"))
                .andExpect(jsonPath("$.items[0].items[0].catalogEntry.description").value("lib"));
    }

    @Test
    void searchFiltersPrereleaseAndPages() throws Exception {
        push(NugetTestPackages.nupkg("Acme.Lib", "1.0.0", "stable"), apiKey).andExpect(status().isCreated());
        push(NugetTestPackages.nupkg("Acme.Lib", "2.0.0-beta.1", "beta"), apiKey).andExpect(status().isCreated());
        push(NugetTestPackages.nupkg("Acme.Tools", "0.1.0-pre", "only prerelease"), apiKey).andExpect(status().isCreated());
        push(NugetTestPackages.nupkg("Zed", "3.0.0", "unrelated"), apiKey).andExpect(status().isCreated());

        mvc.perform(get("/repository/" + REPO + "/v3/query"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(2))
                .andExpect(jsonPath("$.data[0].id").value("Acme.Lib"))
                .andExpect(jsonPath("$.data[0].version").value("1.0.0"))
                .andExpect(jsonPath("$.data[1].id").value("Zed"));
        mvc.perform(get("/repository/" + REPO + "/v3/query").param("prerelease", "true"))
                .andExpect(jsonPath("$.totalHits").value(3))
                .andExpect(jsonPath("$.data[0].version").value("2.0.0-beta.1"))
                .andExpect(jsonPath("$.data[0].versions", hasSize(2)))
                .andExpect(jsonPath("$.data[1].id").value("Acme.Tools"));
        mvc.perform(get("/repository/" + REPO + "/v3/query").param("q", "acme").param("prerelease", "true")
                        .param("skip", "1").param("take", "1"))
                .andExpect(jsonPath("$.totalHits").value(2))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value("Acme.Tools"));
        mvc.perform(get("/repository/" + REPO + "/v3/query").param("q", "packageid:zed"))
                .andExpect(jsonPath("$.totalHits").value(1))
                .andExpect(jsonPath("$.data[0].id").value("Zed"));
        mvc.perform(get("/repository/" + REPO + "/v3/query").param("q", "nothing-like-this"))
                .andExpect(jsonPath("$.totalHits").value(0))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void registrationListsEveryVersionInOrder() throws Exception {
        for (String v : new String[] {"2.0.0", "1.0.0-beta", "1.0.0", "1.10.0", "1.9.0"}) {
            push(NugetTestPackages.nupkg("Acme.Lib", v, "v" + v), apiKey).andExpect(status().isCreated());
        }
        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/acme.lib/index.json"))
                .andExpect(jsonPath("$.versions").value(org.hamcrest.Matchers.contains("1.0.0-beta", "1.0.0", "1.9.0", "1.10.0", "2.0.0")));
        mvc.perform(get("/repository/" + REPO + "/v3/registration/acme.lib/index.json"))
                .andExpect(jsonPath("$.items[0].count").value(5))
                .andExpect(jsonPath("$.items[0].lower").value("1.0.0-beta"))
                .andExpect(jsonPath("$.items[0].upper").value("2.0.0"))
                .andExpect(jsonPath("$.items[0].items[*].catalogEntry.version")
                        .value(org.hamcrest.Matchers.contains("1.0.0-beta", "1.0.0", "1.9.0", "1.10.0", "2.0.0")))
                .andExpect(content().string(not(containsString("api.nuget.org"))));
    }

    @Test
    void deleteRemovesAVersion() throws Exception {
        push(NugetTestPackages.nupkg("Acme.Lib", "1.0.0", "one"), apiKey).andExpect(status().isCreated());
        push(NugetTestPackages.nupkg("Acme.Lib", "1.1.0", "two"), apiKey).andExpect(status().isCreated());

        mvc.perform(delete("/repository/" + REPO + "/v2/package/Acme.Lib/1.0.0")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/repository/" + REPO + "/v2/package/Acme.Lib/1.0").header("X-NuGet-ApiKey", apiKey))
                .andExpect(status().isNoContent());

        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/acme.lib/index.json"))
                .andExpect(jsonPath("$.versions").value(org.hamcrest.Matchers.contains("1.1.0")));
        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/acme.lib/1.0.0/acme.lib.1.0.0.nupkg")).andExpect(status().isNotFound());
        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/acme.lib/1.0.0/acme.lib.nuspec")).andExpect(status().isNotFound());
        mvc.perform(get("/repository/" + REPO + "/v3/registration/acme.lib/1.0.0.json")).andExpect(status().isNotFound());
        mvc.perform(delete("/repository/" + REPO + "/v2/package/Acme.Lib/1.0.0").header("X-NuGet-ApiKey", apiKey))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/repository/" + REPO + "/v2/package/Acme.Lib/1.1.0").header("X-NuGet-ApiKey", apiKey))
                .andExpect(status().isNoContent());
        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/acme.lib/index.json")).andExpect(status().isNotFound());
        mvc.perform(get("/repository/" + REPO + "/v3/registration/acme.lib/index.json")).andExpect(status().isNotFound());
        assertThat(repositories.statistics(repositories.require(REPO))).containsEntry("components", 0L);
    }

    @Test
    void unknownThingsAre404AndWrongMethodsAre405WithJsonBodies() throws Exception {
        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/nope/index.json"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("content.notFound"));
        mvc.perform(get("/repository/" + REPO + "/v3/registration/nope/index.json")).andExpect(status().isNotFound());
        mvc.perform(get("/repository/" + REPO + "/v3/flatcontainer/nope/1.0.0/nope.1.0.0.nupkg")).andExpect(status().isNotFound());
        mvc.perform(get("/repository/" + REPO + "/whatever.txt")).andExpect(status().isNotFound());
        mvc.perform(get("/repository/" + REPO + "/v2/package"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("method.notAllowed"));
    }
}
