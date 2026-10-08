package dev.granary.format.nuget;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the small {@code .nupkg} archives the tests push and parse. */
final class TestNupkgs {

    private TestNupkgs() {
    }

    /** A fluent nuspec, in the 2013 namespace {@code nuget pack} emits. */
    static final class NuspecXml {
        private final String id;
        private final String version;
        private String description = "A test package";
        private String authors = "Granary Tests";
        private String title;
        private String tags;
        private String licenseUrl;
        private String projectUrl;
        private final Map<String, List<String[]>> groups = new LinkedHashMap<>();
        private final List<String[]> flatDependencies = new ArrayList<>();
        private final List<String> packageTypes = new ArrayList<>();

        NuspecXml(String id, String version) {
            this.id = id;
            this.version = version;
        }

        NuspecXml description(String description) {
            this.description = description;
            return this;
        }

        NuspecXml authors(String authors) {
            this.authors = authors;
            return this;
        }

        NuspecXml title(String title) {
            this.title = title;
            return this;
        }

        NuspecXml tags(String tags) {
            this.tags = tags;
            return this;
        }

        NuspecXml licenseUrl(String licenseUrl) {
            this.licenseUrl = licenseUrl;
            return this;
        }

        NuspecXml projectUrl(String projectUrl) {
            this.projectUrl = projectUrl;
            return this;
        }

        NuspecXml dependency(String targetFramework, String depId, String range) {
            groups.computeIfAbsent(targetFramework, k -> new ArrayList<>()).add(new String[] {depId, range});
            return this;
        }

        NuspecXml flatDependency(String depId, String range) {
            flatDependencies.add(new String[] {depId, range});
            return this;
        }

        NuspecXml packageType(String name) {
            packageTypes.add(name);
            return this;
        }

        String xml() {
            StringBuilder sb = new StringBuilder();
            sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
            sb.append("<package xmlns=\"http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd\">\n  <metadata>\n");
            sb.append("    <id>").append(id).append("</id>\n");
            sb.append("    <version>").append(version).append("</version>\n");
            if (title != null) {
                sb.append("    <title>").append(title).append("</title>\n");
            }
            sb.append("    <authors>").append(authors).append("</authors>\n");
            sb.append("    <description>").append(description).append("</description>\n");
            if (tags != null) {
                sb.append("    <tags>").append(tags).append("</tags>\n");
            }
            if (licenseUrl != null) {
                sb.append("    <licenseUrl>").append(licenseUrl).append("</licenseUrl>\n");
            }
            if (projectUrl != null) {
                sb.append("    <projectUrl>").append(projectUrl).append("</projectUrl>\n");
            }
            if (!packageTypes.isEmpty()) {
                sb.append("    <packageTypes>\n");
                for (String type : packageTypes) {
                    sb.append("      <packageType name=\"").append(type).append("\" />\n");
                }
                sb.append("    </packageTypes>\n");
            }
            if (!groups.isEmpty() || !flatDependencies.isEmpty()) {
                sb.append("    <dependencies>\n");
                for (String[] dep : flatDependencies) {
                    sb.append("      <dependency id=\"").append(dep[0]).append("\"");
                    if (dep[1] != null) {
                        sb.append(" version=\"").append(dep[1]).append("\"");
                    }
                    sb.append(" />\n");
                }
                for (Map.Entry<String, List<String[]>> group : groups.entrySet()) {
                    sb.append("      <group");
                    if (group.getKey() != null) {
                        sb.append(" targetFramework=\"").append(group.getKey()).append("\"");
                    }
                    sb.append(">\n");
                    for (String[] dep : group.getValue()) {
                        sb.append("        <dependency id=\"").append(dep[0]).append("\"");
                        if (dep[1] != null) {
                            sb.append(" version=\"").append(dep[1]).append("\"");
                        }
                        sb.append(" exclude=\"Build,Analyzers\" />\n");
                    }
                    sb.append("      </group>\n");
                }
                sb.append("    </dependencies>\n");
            }
            sb.append("  </metadata>\n</package>\n");
            return sb.toString();
        }

        byte[] nupkg() {
            return zip(id + ".nuspec", xml());
        }
    }

    static NuspecXml nuspec(String id, String version) {
        return new NuspecXml(id, version);
    }

    /** A zip laid out like {@code nuget pack} does: manifest at the root, content types, rels and a library. */
    static byte[] zip(String nuspecFileName, String nuspecXml) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"utf-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>");
            entry(zip, "lib/net8.0/library.dll", "MZ not really a dll");
            if (nuspecFileName != null) {
                entry(zip, nuspecFileName, nuspecXml);
            }
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"utf-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>");
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
}
