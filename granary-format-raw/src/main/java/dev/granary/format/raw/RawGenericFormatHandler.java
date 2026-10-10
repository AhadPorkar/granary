package dev.granary.format.raw;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain file storage: installers, archives, build outputs, Debian and RPM files kept as files, anything a
 * {@code curl -T} can upload and a {@code curl -O} can download. Nothing in the path has a meaning to the
 * server, except that paths ending in a slash list a directory.
 */
@org.springframework.stereotype.Component
public class RawGenericFormatHandler extends RawFormatHandler {

    public static final String FORMAT = "raw";

    /** Index-like files that change in place when a directory is used as an apt or yum repository, or "latest" links. */
    static final List<String> DEFAULT_METADATA = List.of(
            "**/latest/**", "**/index.html", "**/index.json",
            "**/Release", "**/InRelease", "**/Release.gpg", "**/Packages", "**/Packages.*", "**/Sources", "**/Sources.*",
            "**/repodata/**");

    public RawGenericFormatHandler(StorageService storage, ProxyService proxy, GroupResolver groups, GranaryProperties properties) {
        super(storage, proxy, groups, properties);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Raw (generic files)";
    }

    @Override
    protected List<String> defaultMetadataPatterns() {
        return DEFAULT_METADATA;
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(RepositoryTemplate.hosted("raw-hosted", FORMAT,
                "Plain files: installers, archives, build outputs, anything curl can upload"));
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext ctx) {
        String url = ctx.url();
        String example = url + "/releases/app/1.2.3/app-1.2.3.zip";
        String auth = ctx.username() + ":" + ctx.token();
        String curlRead = ctx.anonymousRead() ? "" : " -u " + auth;
        List<ConnectSnippet> snippets = new ArrayList<>();
        snippets.add(ConnectSnippet.shell("curl-upload", "curl: upload a file (PUT)",
                "curl -fsS -u " + auth + " -T ./app-1.2.3.zip " + example));
        snippets.add(ConnectSnippet.shell("curl-upload-form", "curl: upload files into a directory (multipart POST)",
                "curl -fsS -u " + auth + " -F file=@app-1.2.3.zip -F file=@app-1.2.3.zip.sha256 " + url + "/releases/app/1.2.3/"));
        snippets.add(ConnectSnippet.shell("curl-download", "curl: download",
                "curl -fsSL" + curlRead + " -O " + example));
        snippets.add(ConnectSnippet.shell("curl-listing", "curl: list a directory as JSON",
                "curl -fsS" + curlRead + " -H 'Accept: application/json' " + url + "/releases/app/"));
        snippets.add(ConnectSnippet.shell("curl-delete", "curl: delete a file or a whole directory",
                "curl -fsS -u " + auth + " -X DELETE " + example + "\n"
                        + "curl -fsS -u " + auth + " -X DELETE " + url + "/releases/app/1.2.3/"));
        snippets.add(ConnectSnippet.shell("wget", "wget: download",
                ctx.anonymousRead()
                        ? "wget " + example
                        : "wget --user=" + ctx.username() + " --password=" + ctx.token() + " " + example));
        snippets.add(ConnectSnippet.file("powershell-download", "PowerShell: download", "shell", null,
                ctx.anonymousRead()
                        ? "Invoke-WebRequest -Uri \"" + example + "\" -OutFile app-1.2.3.zip"
                        : "$cred = Get-Credential -UserName \"" + ctx.username() + "\" -Message \"API token\"\n"
                                + "Invoke-WebRequest -Uri \"" + example + "\" -OutFile app-1.2.3.zip -Credential $cred -AllowUnencryptedAuthentication"));
        snippets.add(ConnectSnippet.file("powershell-upload", "PowerShell: upload", "shell", null,
                "$pair = \"" + auth + "\"\n"
                        + "$headers = @{ Authorization = \"Basic \" + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair)) }\n"
                        + "Invoke-RestMethod -Method Put -Uri \"" + example + "\" -InFile .\\app-1.2.3.zip -ContentType \"application/zip\" -Headers $headers"));
        snippets.add(ConnectSnippet.file("gradle", "Gradle: download during the build", "text", "build.gradle",
                "tasks.register('fetchApp') {\n"
                        + "    def target = layout.buildDirectory.file('downloads/app-1.2.3.zip')\n"
                        + "    outputs.file(target)\n"
                        + "    doLast {\n"
                        + "        def file = target.get().asFile\n"
                        + "        file.parentFile.mkdirs()\n"
                        + "        def connection = new URL('" + example + "').openConnection()\n"
                        + (ctx.anonymousRead() ? ""
                                : "        connection.setRequestProperty('Authorization', 'Basic ' + '" + auth + "'.bytes.encodeBase64())\n")
                        + "        connection.inputStream.withStream { input -> file.withOutputStream { it << input } }\n"
                        + "    }\n"
                        + "}"));
        snippets.add(ConnectSnippet.file("maven", "Maven: download with download-maven-plugin", "xml", "pom.xml",
                "<plugin>\n"
                        + "  <groupId>io.github.download-maven-plugin</groupId>\n"
                        + "  <artifactId>download-maven-plugin</artifactId>\n"
                        + "  <version>2.0.0</version>\n"
                        + "  <executions>\n"
                        + "    <execution>\n"
                        + "      <id>fetch-app</id>\n"
                        + "      <phase>generate-resources</phase>\n"
                        + "      <goals><goal>wget</goal></goals>\n"
                        + "      <configuration>\n"
                        + "        <url>" + example + "</url>\n"
                        + "        <outputDirectory>${project.build.directory}/downloads</outputDirectory>\n"
                        + (ctx.anonymousRead() ? "" : "        <serverId>granary</serverId> <!-- username/token in settings.xml -->\n")
                        + "      </configuration>\n"
                        + "    </execution>\n"
                        + "  </executions>\n"
                        + "</plugin>"));
        snippets.add(ConnectSnippet.shell("vcpkg", "vcpkg: asset and binary caches in this repository",
                "# the dedicated 'vcpkg' format offers the same with identical re-uploads allowed\n"
                        + "export X_VCPKG_ASSET_SOURCES=\"x-azurl," + url + "/vcpkg-assets/,,readwrite\"\n"
                        + "export VCPKG_BINARY_SOURCES=\"clear;http," + url + "/vcpkg-binaries/{name}/{version}/{sha},readwrite\""));
        return snippets;
    }
}
