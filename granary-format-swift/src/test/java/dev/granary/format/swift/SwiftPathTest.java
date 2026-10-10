package dev.granary.format.swift;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.swift.SwiftPath.Kind;
import org.junit.jupiter.api.Test;

class SwiftPathTest {

    @Test
    void fixedEndpoints() {
        assertThat(SwiftPath.parse("").kind()).isEqualTo(Kind.ROOT);
        assertThat(SwiftPath.parse(null).kind()).isEqualTo(Kind.ROOT);
        assertThat(SwiftPath.parse("/").kind()).isEqualTo(Kind.ROOT);
        assertThat(SwiftPath.parse("login").kind()).isEqualTo(Kind.LOGIN);
        assertThat(SwiftPath.parse("login/").kind()).isEqualTo(Kind.LOGIN);
        assertThat(SwiftPath.parse("availability").kind()).isEqualTo(Kind.AVAILABILITY);
        assertThat(SwiftPath.parse("identifiers").kind()).isEqualTo(Kind.IDENTIFIERS);
        assertThat(SwiftPath.parse("favicon.ico").kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void packageReleaseList() {
        SwiftPath p = SwiftPath.parse("mona/LinkedList");
        assertThat(p.kind()).isEqualTo(Kind.PACKAGE);
        assertThat(p.identity()).isEqualTo(new SwiftIdentity("mona", "LinkedList"));
        assertThat(p.version()).isNull();
        assertThat(SwiftPath.parse("mona/LinkedList.json").identity().name()).isEqualTo("LinkedList");
        assertThat(SwiftPath.parse("mona/LinkedList/").kind()).isEqualTo(Kind.PACKAGE);
    }

    @Test
    void releaseArchiveAndManifest() {
        SwiftPath release = SwiftPath.parse("mona/LinkedList/1.1.1");
        assertThat(release.kind()).isEqualTo(Kind.RELEASE);
        assertThat(release.version()).isEqualTo("1.1.1");
        assertThat(SwiftPath.parse("mona/LinkedList/1.1.1.json").version()).isEqualTo("1.1.1");
        assertThat(SwiftPath.parse("mona/LinkedList/1.0.0-beta+exp.sha.5114f85").version()).isEqualTo("1.0.0-beta+exp.sha.5114f85");

        SwiftPath archive = SwiftPath.parse("mona/LinkedList/1.1.1.zip");
        assertThat(archive.kind()).isEqualTo(Kind.ARCHIVE);
        assertThat(archive.version()).isEqualTo("1.1.1");

        SwiftPath manifest = SwiftPath.parse("mona/LinkedList/1.1.1/Package.swift");
        assertThat(manifest.kind()).isEqualTo(Kind.MANIFEST);
        assertThat(manifest.version()).isEqualTo("1.1.1");
        assertThat(manifest.identity().id()).isEqualTo("mona.LinkedList");
    }

    @Test
    void unknownShapes() {
        assertThat(SwiftPath.parse("mona/LinkedList/1.1.1/README.md").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(SwiftPath.parse("a/b/1.0.0/Package.swift/x").kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void invalidPartsCarryAMessage() {
        SwiftPath scope = SwiftPath.parse("-mona/LinkedList");
        assertThat(scope.kind()).isEqualTo(Kind.INVALID);
        assertThat(scope.error()).isEqualTo("'-mona' is not a valid package scope");

        assertThat(SwiftPath.parse("mona/Linked.List.git/1.0.0").error()).isEqualTo("'Linked.List.git' is not a valid package name");
        assertThat(SwiftPath.parse("mona/LinkedList/1.0").error()).isEqualTo("'1.0' is not a valid semantic version");
        assertThat(SwiftPath.parse("mona/LinkedList/v1.0.0.zip").error()).isEqualTo("'v1.0.0' is not a valid semantic version");
        assertThat(SwiftPath.parse("mona/LinkedList/1.x/Package.swift").kind()).isEqualTo(Kind.INVALID);
        assertThat(SwiftPath.parse("mona/" + "x".repeat(300)).error()).endsWith("...' is not a valid package name");
    }

    @Test
    void traversalAndEmptySegmentsAreInvalid() {
        assertThat(SwiftPath.parse("mona/../x").kind()).isEqualTo(Kind.INVALID);
        assertThat(SwiftPath.parse("mona//x").kind()).isEqualTo(Kind.INVALID);
        assertThat(SwiftPath.parse("./mona/x").kind()).isEqualTo(Kind.INVALID);
        assertThat(SwiftPath.parse("mona/x/..").error()).isEqualTo("Illegal path");
    }
}
