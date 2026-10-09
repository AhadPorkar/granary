package dev.granary.format.maven;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MavenSnippetsTest {

    private static final String URL = "https://granary.example.com/repository/maven-public";
    private static final List<String> IDS = List.of("maven-settings", "maven-pom", "maven-deploy", "maven-deploy-file",
            "gradle-kotlin", "gradle-properties", "gradle-groovy", "gradle-publish", "android", "sbt", "sbt-credentials",
            "leiningen");
    private static final Set<String> WITH_CREDENTIALS = Set.of("maven-settings", "gradle-properties", "sbt",
            "sbt-credentials", "leiningen");

    private static ConnectContext context(String username, RepositoryType type) {
        Repository repo = new Repository();
        repo.setName("maven-public");
        repo.setFormat(MavenFormatHandler.FORMAT);
        repo.setType(type);
        return new ConnectContext(repo, URL, "https://granary.example.com", username, ConnectContext.TOKEN_PLACEHOLDER, true);
    }

    private static List<ConnectSnippet> anonymous() {
        return MavenConnectSnippets.of(context(ConnectContext.USERNAME_PLACEHOLDER, RepositoryType.GROUP));
    }

    private static ConnectSnippet snippet(List<ConnectSnippet> snippets, String id) {
        return snippets.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void idsAreStableAndUnique() {
        assertThat(anonymous().stream().map(ConnectSnippet::id)).containsExactlyElementsOf(IDS);
        assertThat(new MavenFormatHandler(null, null, null, null, null)
                .connectSnippets(context("alice", RepositoryType.HOSTED)).stream().map(ConnectSnippet::id))
                .containsExactlyElementsOf(IDS);
    }

    @Test
    void everySnippetNamesTheRepositoryUrlAndHasTitleAndLanguage() {
        for (ConnectSnippet s : anonymous()) {
            assertThat(s.code()).as(s.id()).contains(URL);
            assertThat(s.title()).as(s.id()).isNotBlank();
            assertThat(s.language()).as(s.id()).isIn("shell", "ini", "xml", "json", "yaml", "toml", "text");
            assertThat(s.code()).as(s.id()).doesNotContain(URL + "/");
        }
    }

    @Test
    void credentialSnippetsUsePlaceholdersAndTheOthersCarryNone() {
        for (ConnectSnippet s : anonymous()) {
            if (WITH_CREDENTIALS.contains(s.id())) {
                assertThat(s.code()).as(s.id()).contains(ConnectContext.USERNAME_PLACEHOLDER).contains(ConnectContext.TOKEN_PLACEHOLDER);
            } else {
                assertThat(s.code()).as(s.id()).doesNotContain(ConnectContext.USERNAME_PLACEHOLDER).doesNotContain(ConnectContext.TOKEN_PLACEHOLDER);
            }
        }
    }

    @Test
    void theCallersUsernameReplacesThePlaceholder() {
        List<ConnectSnippet> snippets = MavenConnectSnippets.of(context("alice", RepositoryType.HOSTED));
        for (String id : WITH_CREDENTIALS) {
            assertThat(snippet(snippets, id).code()).as(id).contains("alice").contains(ConnectContext.TOKEN_PLACEHOLDER);
            if (!id.equals("maven-settings")) {
                assertThat(snippet(snippets, id).code()).as(id).doesNotContain(ConnectContext.USERNAME_PLACEHOLDER);
            }
        }
        // the XML element is spelled like the placeholder, so check the value precisely
        assertThat(snippet(snippets, "maven-settings").code())
                .contains("<username>alice</username>")
                .doesNotContain("<username><username></username>");
    }

    @Test
    void mavenSettingsMirrorEverythingAndStoreTheServerCredentials() {
        ConnectSnippet settings = snippet(anonymous(), "maven-settings");
        assertThat(settings.language()).isEqualTo("xml");
        assertThat(settings.filename()).isEqualTo("~/.m2/settings.xml");
        assertThat(settings.code())
                .contains("<mirrorOf>*</mirrorOf>")
                .contains("<url>" + URL + "</url>")
                .contains("<id>granary</id>")
                .contains("<name>Granary maven-public</name>")
                .contains("<username><username></username>")
                .contains("<password><api-token></password>");
    }

    @Test
    void pomAndDeployCommands() {
        List<ConnectSnippet> snippets = anonymous();
        ConnectSnippet pom = snippet(snippets, "maven-pom");
        assertThat(pom.filename()).isEqualTo("pom.xml");
        assertThat(pom.code()).contains("<distributionManagement>").contains("<snapshotRepository>").contains("<url>" + URL + "</url>");
        ConnectSnippet deploy = snippet(snippets, "maven-deploy");
        assertThat(deploy.language()).isEqualTo("shell");
        assertThat(deploy.filename()).isNull();
        assertThat(deploy.code()).startsWith("mvn deploy\n").contains("-DaltDeploymentRepository=granary::" + URL);
        assertThat(snippet(snippets, "maven-deploy-file").code())
                .startsWith("mvn deploy:deploy-file")
                .contains("-DrepositoryId=granary")
                .contains("-Durl=" + URL)
                .contains("-Dfile=").contains("-DgroupId=").contains("-DartifactId=").contains("-Dversion=").contains("-Dpackaging=");
    }

    @Test
    void gradleSettingsCoverPluginsAndDependenciesAndReadCredentialsFromProperties() {
        List<ConnectSnippet> snippets = anonymous();
        ConnectSnippet kotlin = snippet(snippets, "gradle-kotlin");
        assertThat(kotlin.filename()).isEqualTo("settings.gradle.kts");
        assertThat(kotlin.code())
                .contains("pluginManagement {")
                .contains("dependencyResolutionManagement {")
                .contains("url = uri(\"" + URL + "\")")
                .contains("name = \"granary\"")
                .contains("credentials(PasswordCredentials::class)");
        ConnectSnippet groovy = snippet(snippets, "gradle-groovy");
        assertThat(groovy.filename()).isEqualTo("settings.gradle");
        assertThat(groovy.code())
                .contains("pluginManagement {")
                .contains("dependencyResolutionManagement {")
                .contains("url = '" + URL + "'")
                .contains("credentials(PasswordCredentials)")
                .doesNotContain("::class");
        ConnectSnippet properties = snippet(snippets, "gradle-properties");
        assertThat(properties.language()).isEqualTo("ini");
        assertThat(properties.filename()).isEqualTo("~/.gradle/gradle.properties");
        assertThat(properties.code()).contains("granaryUsername=<username>").contains("granaryPassword=<api-token>");
        assertThat(snippet(snippets, "gradle-publish").code()).contains("maven-publish").contains("publishing {").contains(URL);
    }

    @Test
    void androidNoteDependsOnTheRepositoryType() {
        ConnectSnippet group = snippet(anonymous(), "android");
        assertThat(group.code()).contains("maven-public").contains("Google's Maven repository").contains("pluginManagement {");
        ConnectSnippet hosted = snippet(MavenConnectSnippets.of(context("alice", RepositoryType.HOSTED)), "android");
        assertThat(hosted.code()).contains("Point the project at a").contains(URL);
        assertThat(hosted.code()).isNotEqualTo(group.code());
    }

    @Test
    void sbtAndLeiningen() {
        List<ConnectSnippet> snippets = anonymous();
        assertThat(snippet(snippets, "sbt").code())
                .contains("resolvers += \"Granary\" at \"" + URL + "\"")
                .contains("credentials += Credentials(\"Granary\", \"granary.example.com\", \"<username>\", \"<api-token>\")")
                .contains("publishTo := Some(\"Granary\" at \"" + URL + "\")");
        assertThat(snippet(snippets, "sbt-credentials").code())
                .contains("realm=Granary").contains("host=granary.example.com").contains("user=<username>").contains("password=<api-token>");
        ConnectSnippet lein = snippet(snippets, "leiningen");
        assertThat(lein.filename()).isEqualTo("project.clj");
        assertThat(lein.code())
                .contains(":repositories [[\"granary\" {:url \"" + URL + "\"")
                .contains(":username \"<username>\"")
                .contains(":password \"<api-token>\"")
                .contains(":mirrors")
                .contains(":deploy-repositories");
    }

    @Test
    void sbtCredentialsNameTheHostWithoutThePort() {
        Repository repo = new Repository();
        repo.setName("maven-public");
        repo.setFormat(MavenFormatHandler.FORMAT);
        repo.setType(RepositoryType.GROUP);
        ConnectContext ctx = new ConnectContext(repo, "http://localhost:8081/repository/maven-public", "http://localhost:8081",
                "bob", ConnectContext.TOKEN_PLACEHOLDER, false);
        List<ConnectSnippet> snippets = MavenConnectSnippets.of(ctx);
        assertThat(snippet(snippets, "sbt").code())
                .contains("resolvers += \"Granary\" at \"http://localhost:8081/repository/maven-public\"")
                .contains("credentials += Credentials(\"Granary\", \"localhost\", \"bob\", \"<api-token>\")")
                .doesNotContain("\"localhost:8081\"");
        assertThat(snippet(snippets, "sbt-credentials").code()).contains("host=localhost\n").doesNotContain("host=localhost:8081");
        assertThat(MavenConnectSnippets.hostname(ctx)).isEqualTo("localhost");
        ConnectContext ipv6 = new ConnectContext(repo, "http://[::1]:8081/repository/maven-public", "http://[::1]:8081",
                "bob", ConnectContext.TOKEN_PLACEHOLDER, false);
        assertThat(MavenConnectSnippets.hostname(ipv6)).isEqualTo("[::1]");
        ConnectContext plain = new ConnectContext(repo, "https://granary.example.com/repository/maven-public", "https://granary.example.com",
                "bob", ConnectContext.TOKEN_PLACEHOLDER, false);
        assertThat(MavenConnectSnippets.hostname(plain)).isEqualTo("granary.example.com");
    }
}
