package dev.granary.format.swift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SwiftLayoutTest {

    private static final SwiftIdentity ID = new SwiftIdentity("Mona", "LinkedList");

    @Test
    void pathsAreLowerCasedPerPackage() {
        assertThat(SwiftLayout.packagePrefix(ID)).isEqualTo("mona/linkedlist/");
        assertThat(SwiftLayout.archivePath(ID, "1.0.0-Beta")).isEqualTo("mona/linkedlist/1.0.0-Beta.zip");
        assertThat(SwiftLayout.releaseDocumentPath(ID, "1.0.0")).isEqualTo("mona/linkedlist/1.0.0/metadata.json");
        assertThat(SwiftLayout.manifestPath(ID, "1.0.0", "Package@swift-5.swift")).isEqualTo("mona/linkedlist/1.0.0/Package@swift-5.swift");
        assertThat(SwiftLayout.manifestLinksPath(ID, "1.0.0")).isEqualTo("mona/linkedlist/1.0.0/manifest-links.json");
        assertThat(SwiftLayout.signaturePath(ID, "1.0.0")).isEqualTo("mona/linkedlist/1.0.0/source-archive.sig");
        assertThat(SwiftLayout.metadataSignaturePath(ID, "1.0.0")).isEqualTo("mona/linkedlist/1.0.0/metadata.sig");
        assertThat(SwiftLayout.releasesPath(ID)).isEqualTo("mona/linkedlist/releases.json");
    }

    @Test
    void identifierDocumentsAreHashedAndCannotCollideWithPackages() {
        String path = SwiftLayout.identifiersPath("github.com/mona/linkedlist");
        assertThat(path).matches("-/identifiers/[0-9a-f]{64}\\.json");
        assertThat(SwiftLayout.identifiersPath("github.com/mona/linkedlist")).isEqualTo(path);
        assertThat(SwiftLayout.identifiersPath("github.com/mona/other")).isNotEqualTo(path);
        assertThat(SwiftIdentity.isValidScope("-")).isFalse();
    }

    @Test
    void versionOfAReleaseDocument() {
        String prefix = SwiftLayout.packagePrefix(ID);
        assertThat(SwiftLayout.versionOfReleaseDocument(prefix, "mona/linkedlist/1.2.3/metadata.json")).contains("1.2.3");
        assertThat(SwiftLayout.versionOfReleaseDocument(prefix, "mona/linkedlist/1.2.3.zip")).isEmpty();
        assertThat(SwiftLayout.versionOfReleaseDocument(prefix, "mona/linkedlist/1.2.3/Package.swift")).isEmpty();
        assertThat(SwiftLayout.versionOfReleaseDocument(prefix, "mona/linkedlist/x/1.2.3/metadata.json")).isEmpty();
        assertThat(SwiftLayout.versionOfReleaseDocument(prefix, "mona/other/1.2.3/metadata.json")).isEmpty();
        assertThat(SwiftLayout.versionOfReleaseDocument(prefix, null)).isEmpty();
    }

    @Test
    void metadataPaths() {
        assertThat(SwiftLayout.isMetadata("mona/linkedlist/releases.json")).isTrue();
        assertThat(SwiftLayout.isMetadata("mona/linkedlist/1.0.0/metadata.json")).isTrue();
        assertThat(SwiftLayout.isMetadata("-/identifiers/abc.json")).isTrue();
        assertThat(SwiftLayout.isMetadata("mona/linkedlist/1.0.0.zip")).isFalse();
        assertThat(SwiftLayout.isMetadata("mona/linkedlist/1.0.0/Package.swift")).isFalse();
        assertThat(SwiftLayout.isMetadata("mona/linkedlist/1.0.0/manifest-links.json")).isFalse();
        assertThat(SwiftLayout.isMetadata(null)).isFalse();
    }
}
