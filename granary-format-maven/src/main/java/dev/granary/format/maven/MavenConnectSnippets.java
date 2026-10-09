package dev.granary.format.maven;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import java.util.List;

/**
 * Ready-to-paste configuration for every build tool that speaks the Maven 2 layout: Maven itself,
 * Gradle (Kotlin and Groovy DSL), Android projects, sbt and Leiningen.
 *
 * <p>Every snippet names the repository under the id {@code granary}, so the credentials stored once
 * (in {@code settings.xml}, {@code gradle.properties} or {@code ~/.sbt/.credentials}) apply to all of them.
 */
final class MavenConnectSnippets {

    /** The {@code <server>} / repository id shared by all snippets. */
    static final String SERVER_ID = "granary";

    /** The Basic realm the server answers with; sbt and Ivy key credentials by it. */
    static final String REALM = "Granary";

    private MavenConnectSnippets() {
    }

    static List<ConnectSnippet> of(ConnectContext ctx) {
        String url = ctx.url();
        String name = ctx.repository().getName();
        String username = ctx.username();
        String token = ctx.token();
        String host = hostname(ctx);
        return List.of(
                ConnectSnippet.file("maven-settings", "Maven: use this repository as a mirror and store your credentials",
                        "xml", "~/.m2/settings.xml", settingsXml(url, name, username, token)),
                ConnectSnippet.file("maven-pom", "Maven: deploy to this repository", "xml", "pom.xml", distributionManagement(url)),
                ConnectSnippet.shell("maven-deploy", "Maven: deploy a project",
                        "mvn deploy\n"
                                + "# without distributionManagement in the pom:\n"
                                + "mvn deploy -DaltDeploymentRepository=" + SERVER_ID + "::" + url),
                ConnectSnippet.shell("maven-deploy-file", "Maven: deploy a single file",
                        "mvn deploy:deploy-file -DrepositoryId=" + SERVER_ID + " -Durl=" + url
                                + " -Dfile=my-lib-1.0.0.jar -DgroupId=com.example -DartifactId=my-lib -Dversion=1.0.0 -Dpackaging=jar"),
                ConnectSnippet.file("gradle-kotlin", "Gradle (Kotlin DSL): resolve plugins and dependencies from this repository",
                        "text", "settings.gradle.kts", gradleKotlinSettings(url, "")),
                ConnectSnippet.file("gradle-properties", "Gradle: credentials",
                        "ini", "~/.gradle/gradle.properties", gradleProperties(url, username, token)),
                ConnectSnippet.file("gradle-groovy", "Gradle (Groovy DSL): resolve plugins and dependencies from this repository",
                        "text", "settings.gradle", gradleGroovySettings(url)),
                ConnectSnippet.file("gradle-publish", "Gradle: publish to this repository",
                        "text", "build.gradle.kts", gradlePublish(url)),
                ConnectSnippet.file("android", "Android: one repository for the whole project",
                        "text", "settings.gradle.kts", gradleKotlinSettings(url, androidNote(ctx))),
                ConnectSnippet.file("sbt", "sbt: resolver, credentials and publishing",
                        "text", "build.sbt", sbt(url, host, username, token)),
                ConnectSnippet.file("sbt-credentials", "sbt: keep the credentials outside the build",
                        "ini", "~/.sbt/.credentials", sbtCredentials(url, host, username, token)),
                ConnectSnippet.file("leiningen", "Leiningen: resolve and deploy through this repository",
                        "text", "project.clj", leiningen(url, username, token)));
    }

    /** sbt and Ivy match credentials by host name alone, so a port in the server URL must not end up there. */
    static String hostname(ConnectContext ctx) {
        return ctx.host().replaceFirst(":\\d+$", "");
    }

    private static String settingsXml(String url, String name, String username, String token) {
        return "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\">\n"
                + "  <mirrors>\n"
                + "    <mirror>\n"
                + "      <id>" + SERVER_ID + "</id>\n"
                + "      <name>Granary " + name + "</name>\n"
                + "      <mirrorOf>*</mirrorOf>\n"
                + "      <url>" + url + "</url>\n"
                + "    </mirror>\n"
                + "  </mirrors>\n"
                + "  <servers>\n"
                + "    <server>\n"
                + "      <id>" + SERVER_ID + "</id>\n"
                + "      <username>" + username + "</username>\n"
                + "      <password>" + token + "</password>\n"
                + "    </server>\n"
                + "  </servers>\n"
                + "</settings>";
    }

    private static String distributionManagement(String url) {
        return "<distributionManagement>\n"
                + "  <repository>\n"
                + "    <id>" + SERVER_ID + "</id>\n"
                + "    <url>" + url + "</url>\n"
                + "  </repository>\n"
                + "  <snapshotRepository>\n"
                + "    <id>" + SERVER_ID + "</id>\n"
                + "    <url>" + url + "</url>\n"
                + "  </snapshotRepository>\n"
                + "</distributionManagement>";
    }

    private static String gradleKotlinSettings(String url, String preamble) {
        String repository = "        maven {\n"
                + "            name = \"" + SERVER_ID + "\"\n"
                + "            url = uri(\"" + url + "\")\n"
                + "            credentials(PasswordCredentials::class)\n"
                + "        }\n";
        return preamble
                + "pluginManagement {\n"
                + "    repositories {\n"
                + repository
                + "    }\n"
                + "}\n"
                + "\n"
                + "dependencyResolutionManagement {\n"
                + "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n"
                + "    repositories {\n"
                + repository
                + "    }\n"
                + "}";
    }

    private static String gradleGroovySettings(String url) {
        String repository = "        maven {\n"
                + "            name = '" + SERVER_ID + "'\n"
                + "            url = '" + url + "'\n"
                + "            credentials(PasswordCredentials)\n"
                + "        }\n";
        return "pluginManagement {\n"
                + "    repositories {\n"
                + repository
                + "    }\n"
                + "}\n"
                + "\n"
                + "dependencyResolutionManagement {\n"
                + "    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS\n"
                + "    repositories {\n"
                + repository
                + "    }\n"
                + "}";
    }

    private static String gradleProperties(String url, String username, String token) {
        return "# credentials for the repository named \"" + SERVER_ID + "\" (" + url + ")\n"
                + SERVER_ID + "Username=" + username + "\n"
                + SERVER_ID + "Password=" + token;
    }

    private static String gradlePublish(String url) {
        return "plugins {\n"
                + "    `maven-publish`\n"
                + "}\n"
                + "\n"
                + "publishing {\n"
                + "    repositories {\n"
                + "        maven {\n"
                + "            name = \"" + SERVER_ID + "\"\n"
                + "            url = uri(\"" + url + "\")\n"
                + "            credentials(PasswordCredentials::class)\n"
                + "        }\n"
                + "    }\n"
                + "}\n"
                + "\n"
                + "// ./gradlew publishAllPublicationsToGranaryRepository";
    }

    private static String androidNote(ConnectContext ctx) {
        String what = ctx.repository().isGroup()
                ? "// This group is the only repository an Android project needs when it contains the proxies of\n"
                + "// Google's Maven repository, Maven Central, the Gradle Plugin Portal and Clojars, as the standard\n"
                + "// maven-public group does.\n"
                : "// Android projects also need Google's Maven repository and Maven Central. Point the project at a\n"
                + "// group that contains their proxies (the standard maven-public group does) instead of listing them here.\n";
        return what + "\n";
    }

    private static String sbt(String url, String host, String username, String token) {
        return "resolvers += \"" + REALM + "\" at \"" + url + "\"\n"
                + "credentials += Credentials(\"" + REALM + "\", \"" + host + "\", \"" + username + "\", \"" + token + "\")\n"
                + "\n"
                + "publishTo := Some(\"" + REALM + "\" at \"" + url + "\")";
    }

    private static String sbtCredentials(String url, String host, String username, String token) {
        return "# " + url + "\n"
                + "# build.sbt: credentials += Credentials(Path.userHome / \".sbt\" / \".credentials\")\n"
                + "realm=" + REALM + "\n"
                + "host=" + host + "\n"
                + "user=" + username + "\n"
                + "password=" + token;
    }

    private static String leiningen(String url, String username, String token) {
        return ":repositories [[\"" + SERVER_ID + "\" {:url \"" + url + "\"\n"
                + "                           :username \"" + username + "\"\n"
                + "                           :password \"" + token + "\"}]]\n"
                + ":mirrors {#\"central|clojars\" {:name \"" + SERVER_ID + "\" :url \"" + url + "\"}}\n"
                + ":deploy-repositories [[\"" + SERVER_ID + "\" {:url \"" + url + "\" :sign-releases false}]]\n"
                + ";; lein deploy " + SERVER_ID;
    }
}
