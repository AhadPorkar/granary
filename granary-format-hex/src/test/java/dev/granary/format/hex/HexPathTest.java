package dev.granary.format.hex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.format.hex.HexPath.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HexPathTest {

    @Test
    void repositoryEndpoints() {
        assertThat(HexPath.parse("")).isEqualTo(new HexPath(Kind.ROOT, null, null, null));
        assertThat(HexPath.parse("/")).isEqualTo(new HexPath(Kind.ROOT, null, null, null));
        assertThat(HexPath.parse(null).kind()).isEqualTo(Kind.ROOT);
        assertThat(HexPath.parse("names").kind()).isEqualTo(Kind.NAMES);
        assertThat(HexPath.parse("versions").kind()).isEqualTo(Kind.VERSIONS);
        assertThat(HexPath.parse("public_key").kind()).isEqualTo(Kind.PUBLIC_KEY);
        assertThat(HexPath.parse("packages/acme_lib")).isEqualTo(new HexPath(Kind.PACKAGE, "acme_lib", null, null));
        assertThat(HexPath.parse("packages/eLDAP")).isEqualTo(new HexPath(Kind.PACKAGE, "eLDAP", null, null)); // hex.pm allows it
        assertThat(HexPath.parse("tarballs/eLDAP-0.1.0.tar")).isEqualTo(new HexPath(Kind.TARBALL, "eLDAP", "0.1.0", "eLDAP-0.1.0.tar"));
        assertThat(HexPath.parse("/packages/acme_lib/")).isEqualTo(new HexPath(Kind.PACKAGE, "acme_lib", null, null));
        assertThat(HexPath.parse("tarballs/acme_lib-1.0.0-rc.1+build.tar"))
                .isEqualTo(new HexPath(Kind.TARBALL, "acme_lib", "1.0.0-rc.1+build", "acme_lib-1.0.0-rc.1+build.tar"));
        assertThat(HexPath.parse("docs/acme_lib-1.0.0.tar.gz"))
                .isEqualTo(new HexPath(Kind.DOCS, "acme_lib", "1.0.0", "acme_lib-1.0.0.tar.gz"));
        assertThat(HexPath.parse("installs/hex-1.x.csv")).isEqualTo(new HexPath(Kind.INSTALLS, null, null, "hex-1.x.csv"));
        assertThat(HexPath.parse("installs/1.18.0/hex-2.1.1-otp-26.ez").rest()).isEqualTo("1.18.0/hex-2.1.1-otp-26.ez");
    }

    @Test
    void apiEndpoints() {
        assertThat(HexPath.parse("api").kind()).isEqualTo(Kind.API_ROOT);
        assertThat(HexPath.parse("api/").kind()).isEqualTo(Kind.API_ROOT);
        assertThat(HexPath.parse("api/publish").kind()).isEqualTo(Kind.API_PUBLISH);
        assertThat(HexPath.parse("api/packages").kind()).isEqualTo(Kind.API_PACKAGES);
        assertThat(HexPath.parse("api/packages/acme")).isEqualTo(new HexPath(Kind.API_PACKAGE, "acme", null, null));
        assertThat(HexPath.parse("api/packages/acme/releases")).isEqualTo(new HexPath(Kind.API_RELEASES, "acme", null, null));
        assertThat(HexPath.parse("api/packages/acme/releases/1.0.0")).isEqualTo(new HexPath(Kind.API_RELEASE, "acme", "1.0.0", null));
        assertThat(HexPath.parse("api/packages/acme/releases/1.0.0/retire").kind()).isEqualTo(Kind.API_RETIRE);
        assertThat(HexPath.parse("api/packages/acme/releases/1.0.0/docs").kind()).isEqualTo(Kind.API_RELEASE_DOCS);
        assertThat(HexPath.parse("api/users/me").kind()).isEqualTo(Kind.API_USERS_ME);
        assertThat(HexPath.parse("api/auth").kind()).isEqualTo(Kind.API_AUTH);
        assertThat(HexPath.parse("api/keys").kind()).isEqualTo(Kind.API_KEYS);
        assertThat(HexPath.parse("api/keys/my laptop")).isEqualTo(new HexPath(Kind.API_KEY, "my laptop", null, null));
        assertThat(HexPath.parse("api/repos/acme/packages/x/releases").kind()).isEqualTo(Kind.API_RELEASES);
        assertThat(HexPath.parse("api/repos/acme/publish").kind()).isEqualTo(Kind.API_PUBLISH);
        assertThat(HexPath.parse("api/packages/acme").isApi()).isTrue();
        assertThat(HexPath.parse("packages/acme").isApi()).isFalse();
    }

    @Test
    void unknownPathsAreNotInvalid() {
        for (String path : new String[] {"simple", "names/x", "packages", "packages/a/b", "tarballs", "favicon.ico",
                "tarballs/acme-1.0.0/x.tar"}) {
            assertThat(HexPath.parse(path).kind()).as(path).isEqualTo(Kind.UNKNOWN);
        }
        for (String path : new String[] {"api/unknown", "api/users/someone", "api/packages/acme/owners",
                "api/packages/acme/releases/1.0.0/other", "api/auth/x", "api/keys/a/b", "api/publish/x"}) {
            HexPath parsed = HexPath.parse(path);
            assertThat(parsed.kind()).as(path).isEqualTo(Kind.API_UNKNOWN);
            assertThat(parsed.isApi()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../names", "packages/../names", "packages/..", "..", "a//b", "packages\\acme", ".hex",
        ".hex/packages/acme.json", "packages/Acme", "packages/1acme", "packages/acme-lib", "packages/axxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "tarballs/acme.tar", "tarballs/acme-1.0.0.zip", "tarballs/-1.0.0.tar", "tarballs/acme-.tar",
        "docs/acme-1.0.0.tar", "installs/../x", "installs/.hidden", "installs/a b",
        "api/packages/Bad", "api/packages/acme/releases/1.0.0 x", "api/repos/BAD/packages", "api/keys/a\\b",
        "names\u0000", "packages/acme\n"})
    void suspiciousPathsAreInvalid(String path) {
        assertThat(HexPath.parse(path).kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void metadataPaths() {
        assertThat(HexPath.parse("names").isMetadata()).isTrue();
        assertThat(HexPath.parse("versions").isMetadata()).isTrue();
        assertThat(HexPath.parse("packages/acme").isMetadata()).isTrue();
        assertThat(HexPath.parse("public_key").isMetadata()).isTrue();
        assertThat(HexPath.parse("installs/hex-1.x.csv").isMetadata()).isTrue();
        assertThat(HexPath.parse("installs/hex-1.x.csv.signed").isMetadata()).isTrue();
        assertThat(HexPath.parse("installs/1.18.0/hex-2.1.1.ez").isMetadata()).isFalse();
        assertThat(HexPath.parse("tarballs/acme-1.0.0.tar").isMetadata()).isFalse();
        assertThat(HexPath.parse("docs/acme-1.0.0.tar.gz").isMetadata()).isFalse();
        assertThat(HexPath.parse("api/packages").isMetadata()).isFalse();
    }

    @Test
    void storagePaths() {
        assertThat(HexPath.parse("names").storagePath()).isEqualTo("names");
        assertThat(HexPath.parse("versions").storagePath()).isEqualTo("versions");
        assertThat(HexPath.parse("public_key").storagePath()).isEqualTo("public_key");
        assertThat(HexPath.parse("packages/acme").storagePath()).isEqualTo("packages/acme");
        assertThat(HexPath.parse("tarballs/acme-1.0.0.tar").storagePath()).isEqualTo("tarballs/acme-1.0.0.tar");
        assertThat(HexPath.parse("docs/acme-1.0.0.tar.gz").storagePath()).isEqualTo("docs/acme-1.0.0.tar.gz");
        assertThat(HexPath.parse("installs/hex-1.x.csv").storagePath()).isEqualTo("installs/hex-1.x.csv");
        assertThatThrownBy(() -> HexPath.parse("api").storagePath()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void names() {
        assertThat(HexPath.isValidName("a")).isTrue();
        assertThat(HexPath.isValidName("acme_lib2")).isTrue();
        assertThat(HexPath.isValidName("a" + "b".repeat(99))).isTrue();
        assertThat(HexPath.isValidName("a" + "b".repeat(100))).isFalse();
        assertThat(HexPath.isValidName("_acme")).isFalse();
        assertThat(HexPath.isValidName("ächme")).isFalse();
        assertThat(HexPath.isValidName(null)).isFalse();
        assertThat(HexPath.isValidVersionText("1.0.0-rc.1+b")).isTrue();
        assertThat(HexPath.isValidVersionText("1".repeat(101))).isFalse();
        assertThat(HexPath.isValidVersionText(".1")).isFalse();
    }
}
