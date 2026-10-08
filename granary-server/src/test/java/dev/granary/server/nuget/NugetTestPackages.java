package dev.granary.server.nuget;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Packages and nuget.org-shaped documents for the NuGet integration tests. */
final class NugetTestPackages {

    private NugetTestPackages() {
    }

    static String nuspec(String id, String version, String description) {
        return nuspec(id, version, description, null, null);
    }

    /** A nuspec as {@code dotnet pack} writes it; {@code depId}/{@code depRange} add one net8.0 dependency. */
    static String nuspec(String id, String version, String description, String depId, String depRange) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<package xmlns=\"http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd\">\n  <metadata>\n");
        sb.append("    <id>").append(id).append("</id>\n");
        sb.append("    <version>").append(version).append("</version>\n");
        sb.append("    <authors>Granary Tests</authors>\n");
        sb.append("    <description>").append(description).append("</description>\n");
        sb.append("    <tags>granary test</tags>\n");
        sb.append("    <projectUrl>https://example.com/").append(id).append("</projectUrl>\n");
        if (depId != null) {
            sb.append("    <dependencies>\n      <group targetFramework=\"net8.0\">\n");
            sb.append("        <dependency id=\"").append(depId).append("\" version=\"").append(depRange).append("\" />\n");
            sb.append("      </group>\n    </dependencies>\n");
        }
        sb.append("  </metadata>\n</package>\n");
        return sb.toString();
    }

    static byte[] nupkg(String id, String version, String description) {
        return zip(id + ".nuspec", nuspec(id, version, description), null);
    }

    static byte[] nupkg(String id, String version, String description, String depId, String depRange) {
        return zip(id + ".nuspec", nuspec(id, version, description, depId, depRange), null);
    }

    /** A symbol package: same manifest with the {@code SymbolsPackage} type and a pdb instead of a dll. */
    static byte[] snupkg(String id, String version) {
        String xml = nuspec(id, version, "symbols").replace("</metadata>",
                "    <packageTypes><packageType name=\"SymbolsPackage\" /></packageTypes>\n  </metadata>");
        return zip(id + ".nuspec", xml, "lib/net8.0/" + id + ".pdb");
    }

    static byte[] zip(String nuspecFileName, String nuspecXml, String extraEntry) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            entry(zip, "_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>");
            entry(zip, "lib/net8.0/library.dll", "MZ not really a dll " + nuspecFileName);
            if (extraEntry != null) {
                entry(zip, extraEntry, "pdb bytes");
            }
            if (nuspecFileName != null) {
                entry(zip, nuspecFileName, nuspecXml);
            }
            entry(zip, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static byte[] gzip(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // --- nuget.org-shaped documents served by the WireMock fake ------------------------------

    /** A service index with absolute resource URLs on {@code base}, laid out like nuget.org's. */
    static String serviceIndex(String base) {
        return """
                {"version": "3.0.0", "resources": [
                  {"@id": "%1$s/v3-flatcontainer/", "@type": "PackageBaseAddress/3.0.0", "comment": "flat"},
                  {"@id": "%1$s/v3/registration5-semver1/", "@type": "RegistrationsBaseUrl", "comment": "reg"},
                  {"@id": "%1$s/v3/registration5-gz-semver2/", "@type": "RegistrationsBaseUrl/3.6.0", "comment": "reg2"},
                  {"@id": "%1$s/query", "@type": ["SearchQueryService", "SearchQueryService/3.5.0"], "comment": "search"},
                  {"@id": "%1$s/api/v2/package", "@type": "PackagePublish/2.0.0", "comment": "publish"}
                ]}""".formatted(base);
    }

    static String flatIndex(String... versions) {
        StringBuilder sb = new StringBuilder("{\"versions\":[");
        for (int i = 0; i < versions.length; i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(versions[i]).append('"');
        }
        return sb.append("]}").toString();
    }

    /** A registration index whose single page is only referenced, the way nuget.org does for big packages. */
    static String registrationIndexWithRemotePage(String base, String id, String lower, String upper) {
        String reg = base + "/v3/registration5-gz-semver2/" + id;
        return """
                {"@id": "%1$s/index.json", "@type": ["catalog:CatalogRoot", "PackageRegistration", "catalog:Permalink"],
                 "count": 1, "items": [
                   {"@id": "%1$s/page/%2$s/%3$s.json", "@type": "catalog:CatalogPage", "count": 0,
                    "lower": "%2$s", "upper": "%3$s", "parent": "%1$s/index.json"}
                 ]}""".formatted(reg, lower, upper);
    }

    /** A registration index with its page inlined. */
    static String registrationIndexInlined(String base, String id, String... versions) {
        String reg = base + "/v3/registration5-gz-semver2/" + id;
        return """
                {"@id": "%1$s/index.json", "@type": ["catalog:CatalogRoot", "PackageRegistration", "catalog:Permalink"],
                 "count": 1, "items": [%2$s]}""".formatted(reg, registrationPage(base, id, versions));
    }

    static String registrationPage(String base, String id, String... versions) {
        String reg = base + "/v3/registration5-gz-semver2/" + id;
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < versions.length; i++) {
            items.append(i == 0 ? "" : ",").append(registrationLeaf(base, id, versions[i]));
        }
        return """
                {"@id": "%1$s/page/%2$s/%3$s.json", "@type": "catalog:CatalogPage", "count": %4$d,
                 "lower": "%2$s", "upper": "%3$s", "parent": "%1$s/index.json", "items": [%5$s]}"""
                .formatted(reg, versions[0], versions[versions.length - 1], versions.length, items);
    }

    static String registrationLeaf(String base, String id, String version) {
        String reg = base + "/v3/registration5-gz-semver2/" + id;
        String content = base + "/v3-flatcontainer/" + id + "/" + version + "/" + id + "." + version + ".nupkg";
        return """
                {"@id": "%1$s/%3$s.json", "@type": "Package", "commitId": "c-%3$s",
                 "catalogEntry": {"@id": "%4$s/v3/catalog0/data/2026.01.01/%2$s.%3$s.json", "@type": "PackageDetails",
                   "id": "%2$s", "version": "%3$s", "description": "upstream %2$s %3$s", "authors": "Upstream",
                   "listed": true, "published": "2026-01-01T00:00:00+00:00", "packageContent": "%5$s",
                   "dependencyGroups": [{"@type": "PackageDependencyGroup", "targetFramework": "net8.0",
                     "dependencies": [{"@type": "PackageDependency", "id": "Newtonsoft.Json", "range": "[13.0.1, )",
                       "registration": "%4$s/v3/registration5-gz-semver2/newtonsoft.json/index.json"}]}]},
                 "packageContent": "%5$s", "registration": "%1$s/index.json"}""".formatted(reg, id, version, base, content);
    }

    static String searchResult(String base, String id, String version) {
        String reg = base + "/v3/registration5-gz-semver2/" + id.toLowerCase();
        return """
                {"totalHits": 1, "data": [
                  {"@id": "%1$s/index.json", "@type": "Package", "registration": "%1$s/index.json",
                   "id": "%2$s", "version": "%3$s", "description": "upstream search hit", "totalDownloads": 42,
                   "versions": [{"version": "%3$s", "downloads": 42, "@id": "%1$s/%3$s.json"}]}
                ]}""".formatted(reg, id, version);
    }
}
