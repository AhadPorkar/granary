package dev.granary.format.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MavenMetadataTest {

    static final String ARTIFACT_LEVEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>junit</groupId>
              <artifactId>junit</artifactId>
              <versioning>
                <latest>4.13.2</latest>
                <release>4.13.2</release>
                <versions>
                  <version>4.12</version>
                  <version>4.13</version>
                  <version>4.13.2</version>
                </versions>
                <lastUpdated>20210213174433</lastUpdated>
              </versioning>
            </metadata>
            """;

    static final String SNAPSHOT_LEVEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata modelVersion="1.1.0">
              <groupId>com.acme</groupId>
              <artifactId>lib</artifactId>
              <version>1.0-SNAPSHOT</version>
              <versioning>
                <snapshot>
                  <timestamp>20260101.120000</timestamp>
                  <buildNumber>3</buildNumber>
                </snapshot>
                <lastUpdated>20260101120000</lastUpdated>
                <snapshotVersions>
                  <snapshotVersion>
                    <extension>jar</extension>
                    <value>1.0-20260101.120000-3</value>
                    <updated>20260101120000</updated>
                  </snapshotVersion>
                  <snapshotVersion>
                    <classifier>sources</classifier>
                    <extension>jar</extension>
                    <value>1.0-20260101.120000-3</value>
                    <updated>20260101120000</updated>
                  </snapshotVersion>
                </snapshotVersions>
              </versioning>
            </metadata>
            """;

    @Test
    void parsesArtifactLevelMetadata() throws IOException {
        MavenMetadata md = MavenMetadata.parse(ARTIFACT_LEVEL.getBytes(StandardCharsets.UTF_8));
        assertThat(md.getGroupId()).isEqualTo("junit");
        assertThat(md.getArtifactId()).isEqualTo("junit");
        assertThat(md.getLatest()).isEqualTo("4.13.2");
        assertThat(md.getRelease()).isEqualTo("4.13.2");
        assertThat(md.getVersions()).containsExactly("4.12", "4.13", "4.13.2");
        assertThat(md.getLastUpdated()).isEqualTo("20210213174433");
        assertThat(md.getSnapshotVersions()).isEmpty();
    }

    @Test
    void parsesSnapshotMetadata() throws IOException {
        MavenMetadata md = MavenMetadata.parse(SNAPSHOT_LEVEL.getBytes(StandardCharsets.UTF_8));
        assertThat(md.getVersion()).isEqualTo("1.0-SNAPSHOT");
        assertThat(md.getSnapshotTimestamp()).isEqualTo("20260101.120000");
        assertThat(md.getSnapshotBuildNumber()).isEqualTo("3");
        assertThat(md.getSnapshotVersions()).hasSize(2);
        assertThat(md.getSnapshotVersions().get(1).classifier()).isEqualTo("sources");
        assertThat(md.getSnapshotVersions().get(1).key()).isEqualTo("sources:jar");
        assertThat(md.getSnapshotVersions().get(0).key()).isEqualTo(":jar");
    }

    @Test
    void roundTripsThroughXml() throws IOException {
        MavenMetadata md = MavenMetadata.parse(SNAPSHOT_LEVEL.getBytes(StandardCharsets.UTF_8));
        String xml = md.toXml();
        MavenMetadata again = MavenMetadata.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(again.toXml()).isEqualTo(xml);
        assertThat(xml).contains("modelVersion=\"1.1.0\"").contains("<buildNumber>3</buildNumber>");
    }

    @Test
    void writesPluginsBlock() throws IOException {
        MavenMetadata md = new MavenMetadata();
        md.getPlugins().add(new MavenMetadata.Plugin("Compiler", "compiler", "maven-compiler-plugin"));
        String xml = md.toXml();
        assertThat(xml).contains("<plugins><plugin><name>Compiler</name><prefix>compiler</prefix>");
        MavenMetadata parsed = MavenMetadata.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.getPlugins()).containsExactly(new MavenMetadata.Plugin("Compiler", "compiler", "maven-compiler-plugin"));
    }

    @Test
    void rejectsMalformedXmlAndExternalEntities() {
        assertThatThrownBy(() -> MavenMetadata.parse("<metadata>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class);
        String xxe = "<!DOCTYPE m [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><metadata><groupId>&x;</groupId></metadata>";
        assertThatThrownBy(() -> MavenMetadata.parse(xxe.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IOException.class);
    }

    @Test
    void mergesVersionsAcrossSources() throws IOException {
        MavenMetadata a = MavenMetadata.parse(ARTIFACT_LEVEL.getBytes(StandardCharsets.UTF_8));
        MavenMetadata b = new MavenMetadata();
        b.setGroupId("junit");
        b.setArtifactId("junit");
        b.getVersions().addAll(List.of("4.13.2", "5.0.0-SNAPSHOT", "4.11"));
        b.setLastUpdated("20260101000000");

        MavenMetadata merged = MavenMetadataMerger.merge(List.of(a, b));

        assertThat(merged.getVersions()).containsExactly("4.11", "4.12", "4.13", "4.13.2", "5.0.0-SNAPSHOT");
        assertThat(merged.getLatest()).isEqualTo("5.0.0-SNAPSHOT");
        assertThat(merged.getRelease()).isEqualTo("4.13.2");
        assertThat(merged.getLastUpdated()).isEqualTo("20260101000000");
        assertThat(merged.getGroupId()).isEqualTo("junit");
    }

    @Test
    void mergesSnapshotVersionsPreferringTheNewestBuild() throws IOException {
        MavenMetadata older = MavenMetadata.parse(SNAPSHOT_LEVEL.getBytes(StandardCharsets.UTF_8));
        MavenMetadata newer = new MavenMetadata();
        newer.setGroupId("com.acme");
        newer.setArtifactId("lib");
        newer.setVersion("1.0-SNAPSHOT");
        newer.setSnapshotTimestamp("20260102.080000");
        newer.setSnapshotBuildNumber("1");
        newer.setLastUpdated("20260102080000");
        newer.getSnapshotVersions().add(new MavenMetadata.SnapshotVersion(null, "jar", "1.0-20260102.080000-1", "20260102080000"));
        newer.getSnapshotVersions().add(new MavenMetadata.SnapshotVersion(null, "pom", "1.0-20260102.080000-1", "20260102080000"));

        MavenMetadata merged = MavenMetadataMerger.merge(List.of(older, newer));

        assertThat(merged.getSnapshotTimestamp()).isEqualTo("20260102.080000");
        assertThat(merged.getSnapshotBuildNumber()).isEqualTo("1");
        assertThat(merged.getSnapshotVersions()).extracting(MavenMetadata.SnapshotVersion::key)
                .containsExactly(":jar", "sources:jar", ":pom");
        assertThat(merged.getSnapshotVersions().get(0).value()).isEqualTo("1.0-20260102.080000-1");
        assertThat(merged.getSnapshotVersions().get(1).value()).isEqualTo("1.0-20260101.120000-3");
    }

    @Test
    void mergeOfNothingIsEmpty() {
        MavenMetadata merged = MavenMetadataMerger.merge(List.of());
        assertThat(merged.getVersions()).isEmpty();
        assertThat(merged.getLatest()).isNull();
        assertThat(merged.toXml()).contains("<metadata>");
    }
}
