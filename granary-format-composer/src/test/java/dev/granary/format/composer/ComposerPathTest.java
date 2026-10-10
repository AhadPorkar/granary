package dev.granary.format.composer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.composer.ComposerPath.Kind;
import org.junit.jupiter.api.Test;

class ComposerPathTest {

    @Test
    void parsesTheRootDocuments() {
        assertThat(ComposerPath.parse("").kind()).isEqualTo(Kind.ROOT);
        assertThat(ComposerPath.parse("/").kind()).isEqualTo(Kind.ROOT);
        assertThat(ComposerPath.parse(null).kind()).isEqualTo(Kind.ROOT);
        assertThat(ComposerPath.parse("packages.json").kind()).isEqualTo(Kind.PACKAGES_JSON);
        assertThat(ComposerPath.parse("search.json").kind()).isEqualTo(Kind.SEARCH);
        assertThat(ComposerPath.parse("list.json").kind()).isEqualTo(Kind.LIST);
        assertThat(ComposerPath.parse("downloads").kind()).isEqualTo(Kind.DOWNLOADS);
        assertThat(ComposerPath.parse("downloads/").kind()).isEqualTo(Kind.DOWNLOADS);
        assertThat(ComposerPath.parse("api/security-advisories").kind()).isEqualTo(Kind.ADVISORIES);
        assertThat(ComposerPath.parse("api/security-advisories/").kind()).isEqualTo(Kind.ADVISORIES);
        assertThat(ComposerPath.parse("packages").kind()).isEqualTo(Kind.PACKAGES);
    }

    @Test
    void parsesPackageMetadata() {
        ComposerPath releases = ComposerPath.parse("p2/psr/log.json");
        assertThat(releases.kind()).isEqualTo(Kind.P2);
        assertThat(releases.name()).isEqualTo("psr/log");
        assertThat(releases.dev()).isFalse();
        assertThat(releases.metadataFileName()).isEqualTo("psr/log");

        ComposerPath branches = ComposerPath.parse("p2/psr/log~dev.json");
        assertThat(branches.kind()).isEqualTo(Kind.P2);
        assertThat(branches.name()).isEqualTo("psr/log");
        assertThat(branches.dev()).isTrue();
        assertThat(branches.metadataFileName()).isEqualTo("psr/log~dev");

        assertThat(ComposerPath.parse("p2/Acme/Some.Lib_x--y.json").name()).isEqualTo("acme/some.lib_x--y");
        assertThat(ComposerPath.parse("p2/psr/log").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(ComposerPath.parse("p2/psr.json").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(ComposerPath.parse("p2/psr/log/extra.json").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(ComposerPath.parse("p2/-bad/name.json").kind()).isEqualTo(Kind.INVALID);
        assertThat(ComposerPath.parse("p2/a/b c.json").kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void parsesProviders() {
        ComposerPath path = ComposerPath.parse("providers/psr/log-implementation.json");
        assertThat(path.kind()).isEqualTo(Kind.PROVIDERS);
        assertThat(path.name()).isEqualTo("psr/log-implementation");
    }

    @Test
    void parsesDistArchives() {
        ComposerPath path = ComposerPath.parse("dist/psr/log/3.0.2/f16e1d5863e37f8d8c2a01719f5b34baa2b714d3.zip");
        assertThat(path.kind()).isEqualTo(Kind.DIST);
        assertThat(path.name()).isEqualTo("psr/log");
        assertThat(path.version()).isEqualTo("3.0.2");
        assertThat(path.file()).isEqualTo("f16e1d5863e37f8d8c2a01719f5b34baa2b714d3.zip");
        assertThat(path.dev()).isFalse();

        ComposerPath branch = ComposerPath.parse("dist/acme/lib/dev-feature/login/abc.zip");
        assertThat(branch.version()).isEqualTo("dev-feature/login");
        assertThat(branch.dev()).isTrue();

        assertThat(ComposerPath.parse("dist/psr/log/3.0.2").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(ComposerPath.parse("dist/psr/../3.0.2/x.zip").kind()).isEqualTo(Kind.INVALID);
        assertThat(ComposerPath.parse("dist/psr/log/./x.zip").kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void parsesUploadsAndDeletes() {
        ComposerPath version = ComposerPath.parse("packages/acme/lib/1.0.0");
        assertThat(version.kind()).isEqualTo(Kind.PACKAGE_VERSION);
        assertThat(version.name()).isEqualTo("acme/lib");
        assertThat(version.version()).isEqualTo("1.0.0");

        assertThat(ComposerPath.parse("packages/acme/lib/1.0.0.zip").version()).isEqualTo("1.0.0");
        assertThat(ComposerPath.parse("packages/acme/lib/dev-main").dev()).isTrue();
        assertThat(ComposerPath.parse("packages/acme/lib").kind()).isEqualTo(Kind.PACKAGE);
        assertThat(ComposerPath.parse("packages/acme").kind()).isEqualTo(Kind.UNKNOWN);

        ComposerPath nexus = ComposerPath.parse("packages/upload/acme/lib/2.1.0");
        assertThat(nexus.kind()).isEqualTo(Kind.PACKAGE_VERSION);
        assertThat(nexus.name()).isEqualTo("acme/lib");
        assertThat(nexus.version()).isEqualTo("2.1.0");
        assertThat(ComposerPath.parse("packages/upload/lib").kind()).isEqualTo(Kind.PACKAGE);
    }

    @Test
    void rejectsTraversalAndOddCharacters() {
        assertThat(ComposerPath.parse("p2/../../etc/passwd.json").kind()).isEqualTo(Kind.INVALID);
        assertThat(ComposerPath.parse("p2\\psr\\log.json").kind()).isEqualTo(Kind.INVALID);
        assertThat(ComposerPath.parse("p2/psr/log.json\0").kind()).isEqualTo(Kind.INVALID);
        assertThat(ComposerPath.parse("p2//log.json").kind()).isEqualTo(Kind.INVALID);
        assertThat(ComposerPath.parse("packages/acme/lib/..").kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void unknownPathsAreUnknown() {
        assertThat(ComposerPath.parse("p/psr/log$abc.json").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(ComposerPath.parse("index.html").kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void indexDocumentsAreMetadata() {
        assertThat(ComposerPath.parse("").isMetadata()).isTrue();
        assertThat(ComposerPath.parse("packages.json").isMetadata()).isTrue();
        assertThat(ComposerPath.parse("p2/psr/log.json").isMetadata()).isTrue();
        assertThat(ComposerPath.parse("p2/psr/log~dev.json").isMetadata()).isTrue();
        assertThat(ComposerPath.parse("search.json").isMetadata()).isTrue();
        assertThat(ComposerPath.parse("list.json").isMetadata()).isTrue();
        assertThat(ComposerPath.parse("providers/psr/log.json").isMetadata()).isTrue();
        assertThat(ComposerPath.parse("dist/psr/log/3.0.2/abc.zip").isMetadata()).isFalse();
        assertThat(ComposerPath.parse("packages/acme/lib/1.0.0").isMetadata()).isFalse();
    }

    @Test
    void buildsStoragePaths() {
        assertThat(ComposerPath.metadataPath("psr/log", false)).isEqualTo("p2/psr/log.json");
        assertThat(ComposerPath.metadataPath("psr/log", true)).isEqualTo("p2/psr/log~dev.json");
        assertThat(ComposerPath.distPath("psr/log", "3.0.2", "abc.zip")).isEqualTo("dist/psr/log/3.0.2/abc.zip");
    }
}
