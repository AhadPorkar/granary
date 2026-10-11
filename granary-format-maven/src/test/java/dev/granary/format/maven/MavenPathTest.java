package dev.granary.format.maven;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MavenPathTest {

    @Test
    void parsesReleaseArtifacts() {
        MavenPath p = MavenPath.parse("org/springframework/spring-core/6.2.0/spring-core-6.2.0.jar");
        assertThat(p.kind()).isEqualTo(MavenPath.Kind.ARTIFACT);
        assertThat(p.groupId()).isEqualTo("org.springframework");
        assertThat(p.artifactId()).isEqualTo("spring-core");
        assertThat(p.version()).isEqualTo("6.2.0");
        assertThat(p.classifier()).isNull();
        assertThat(p.extension()).isEqualTo("jar");
        assertThat(p.isSnapshot()).isFalse();
        assertThat(p.isMutable()).isFalse();
    }

    @Test
    void parsesClassifiersAndMultiDotExtensions() {
        MavenPath sources = MavenPath.parse("com/acme/lib/1.0/lib-1.0-sources.jar");
        assertThat(sources.classifier()).isEqualTo("sources");
        assertThat(sources.extension()).isEqualTo("jar");

        MavenPath dist = MavenPath.parse("com/acme/lib/1.0/lib-1.0-bin.tar.gz");
        assertThat(dist.classifier()).isEqualTo("bin");
        assertThat(dist.extension()).isEqualTo("tar.gz");

        MavenPath signature = MavenPath.parse("com/acme/lib/1.0/lib-1.0.pom.asc");
        assertThat(signature.kind()).isEqualTo(MavenPath.Kind.ARTIFACT);
        assertThat(signature.extension()).isEqualTo("pom.asc");
    }

    @Test
    void artifactIdsAndVersionsContainingHyphensAndDots() {
        MavenPath p = MavenPath.parse("io/github/x/my-lib-core/2.0.0-beta.1/my-lib-core-2.0.0-beta.1-tests.jar");
        assertThat(p.kind()).isEqualTo(MavenPath.Kind.ARTIFACT);
        assertThat(p.artifactId()).isEqualTo("my-lib-core");
        assertThat(p.version()).isEqualTo("2.0.0-beta.1");
        assertThat(p.classifier()).isEqualTo("tests");
    }

    @Test
    void parsesSnapshots() {
        MavenPath plain = MavenPath.parse("com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar");
        assertThat(plain.kind()).isEqualTo(MavenPath.Kind.ARTIFACT);
        assertThat(plain.isSnapshot()).isTrue();
        assertThat(plain.isMutableSnapshotArtifact()).isTrue();
        assertThat(plain.isMutable()).isTrue();

        MavenPath stamped = MavenPath.parse("com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260101.120000-3-sources.jar");
        assertThat(stamped.kind()).isEqualTo(MavenPath.Kind.ARTIFACT);
        assertThat(stamped.version()).isEqualTo("1.0-SNAPSHOT");
        assertThat(stamped.classifier()).isEqualTo("sources");
        assertThat(stamped.extension()).isEqualTo("jar");
        assertThat(stamped.isTimestampedSnapshot()).isTrue();
        assertThat(stamped.isMutable()).isFalse();
        assertThat(stamped.snapshotTimestamp()).contains("20260101.120000-3");
    }

    @Test
    void parsesMetadataAtArtifactAndVersionLevel() {
        MavenPath artifactLevel = MavenPath.parse("com/acme/lib/maven-metadata.xml");
        assertThat(artifactLevel.kind()).isEqualTo(MavenPath.Kind.METADATA);
        assertThat(artifactLevel.groupId()).isEqualTo("com.acme");
        assertThat(artifactLevel.artifactId()).isEqualTo("lib");
        assertThat(artifactLevel.isArtifactLevelMetadata()).isTrue();
        assertThat(artifactLevel.isMutable()).isTrue();

        MavenPath versionLevel = MavenPath.parse("com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml");
        assertThat(versionLevel.isVersionLevelMetadata()).isTrue();
        assertThat(versionLevel.version()).isEqualTo("1.0-SNAPSHOT");
        assertThat(versionLevel.artifactId()).isEqualTo("lib");

        MavenPath groupLevel = MavenPath.parse("org/apache/maven/plugins/maven-metadata.xml");
        assertThat(groupLevel.kind()).isEqualTo(MavenPath.Kind.METADATA);
    }

    @Test
    void parsesChecksumsOfArtifactsAndMetadata() {
        MavenPath sha1 = MavenPath.parse("com/acme/lib/1.0/lib-1.0.jar.sha1");
        assertThat(sha1.kind()).isEqualTo(MavenPath.Kind.CHECKSUM);
        assertThat(sha1.checksumAlgorithm()).isEqualTo("sha1");
        assertThat(sha1.checksumOf()).isEqualTo("com/acme/lib/1.0/lib-1.0.jar");
        assertThat(sha1.artifactId()).isEqualTo("lib");
        assertThat(sha1.isMutable()).isFalse();

        MavenPath md5 = MavenPath.parse("com/acme/lib/maven-metadata.xml.MD5");
        assertThat(md5.kind()).isEqualTo(MavenPath.Kind.CHECKSUM);
        assertThat(md5.checksumAlgorithm()).isEqualTo("md5");
        assertThat(md5.isMutable()).isTrue();

        MavenPath sha512 = MavenPath.parse("com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar.sha512");
        assertThat(sha512.isMutable()).isTrue();
    }

    @Test
    void directoriesAndOddPaths() {
        assertThat(MavenPath.parse("").kind()).isEqualTo(MavenPath.Kind.DIRECTORY);
        assertThat(MavenPath.parse("com/acme/").kind()).isEqualTo(MavenPath.Kind.DIRECTORY);
        assertThat(MavenPath.parse("archetype-catalog.xml").kind()).isEqualTo(MavenPath.Kind.OTHER);
        assertThat(MavenPath.parse(".index/nexus-maven-repository-index.gz").kind()).isEqualTo(MavenPath.Kind.OTHER);
        assertThat(MavenPath.parse("com/acme/lib/1.0/README.txt").kind()).isEqualTo(MavenPath.Kind.OTHER);
        assertThat(MavenPath.parse("com/acme/lib/1.0/lib-2.0.jar").kind()).isEqualTo(MavenPath.Kind.OTHER);
        assertThat(MavenPath.parse("com/acme/lib/1.0/lib-1.0").kind()).isEqualTo(MavenPath.Kind.OTHER);
        assertThat(MavenPath.parse(null).kind()).isEqualTo(MavenPath.Kind.DIRECTORY);
    }

    @Test
    void artifactsAndTheirChecksumsBelongToTheirComponent() {
        assertThat(component("org/springframework/spring-core/6.2.0/spring-core-6.2.0.jar"))
                .isEqualTo(coordinates("org.springframework", "spring-core", "6.2.0"));
        assertThat(component("com/acme/lib/1.0/lib-1.0-sources.jar.sha1")).isEqualTo(coordinates("com.acme", "lib", "1.0"));
        assertThat(component("com/acme/lib/1.0/lib-1.0.pom.asc")).isEqualTo(coordinates("com.acme", "lib", "1.0"));
        assertThat(component("com/acme/lib/1.0/lib-1.0.pom.asc.sha256")).isEqualTo(coordinates("com.acme", "lib", "1.0"));
        // timestamped and plain snapshot files belong to the snapshot version, like hosted deployments
        assertThat(component("com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260101.120000-3.jar"))
                .isEqualTo(coordinates("com.acme", "lib", "1.0-SNAPSHOT"));
        assertThat(component("com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar.md5"))
                .isEqualTo(coordinates("com.acme", "lib", "1.0-SNAPSHOT"));
        assertThat(component("gradle/plugin/com/gradleup/shadow/com.gradleup.shadow.gradle.plugin/9.0.0/com.gradleup.shadow.gradle.plugin-9.0.0.pom"))
                .isEqualTo(coordinates("gradle.plugin.com.gradleup.shadow", "com.gradleup.shadow.gradle.plugin", "9.0.0"));
    }

    @Test
    void metadataChecksumsOfChecksumsAndOtherFilesBelongToNoComponent() {
        assertThat(MavenPath.parse("com/acme/lib/maven-metadata.xml").component()).isEmpty();
        assertThat(MavenPath.parse("com/acme/lib/maven-metadata.xml.sha1").component()).isEmpty();
        assertThat(MavenPath.parse("com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml").component()).isEmpty();
        assertThat(MavenPath.parse("com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml.md5").component()).isEmpty();
        assertThat(MavenPath.parse("com/acme/lib/1.0/lib-1.0.jar.sha1.md5").component()).isEmpty();
        assertThat(MavenPath.parse("archetype-catalog.xml").component()).isEmpty();
        assertThat(MavenPath.parse("archetype-catalog.xml.sha1").component()).isEmpty();
        assertThat(MavenPath.parse(".index/nexus-maven-repository-index.gz").component()).isEmpty();
        assertThat(MavenPath.parse("com/acme/lib/1.0/README.txt").component()).isEmpty();
        assertThat(MavenPath.parse("com/acme/lib/1.0/lib-2.0.jar").component()).isEmpty();
        assertThat(MavenPath.parse("com/acme/").component()).isEmpty();
        assertThat(MavenPath.parse("").component()).isEmpty();
        assertThat(MavenPath.parse(null).component()).isEmpty();
    }

    private static dev.granary.core.storage.StorageService.ComponentCoordinates component(String path) {
        return MavenPath.parse(path).component().orElseThrow();
    }

    private static dev.granary.core.storage.StorageService.ComponentCoordinates coordinates(String g, String a, String v) {
        return new dev.granary.core.storage.StorageService.ComponentCoordinates(g, a, v);
    }
}
