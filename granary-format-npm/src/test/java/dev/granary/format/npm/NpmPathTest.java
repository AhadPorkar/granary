package dev.granary.format.npm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.npm.NpmPath.Kind;
import org.junit.jupiter.api.Test;

class NpmPathTest {

    @Test
    void unscopedPackument() {
        NpmPath p = NpmPath.parse("lodash");
        assertThat(p.kind()).isEqualTo(Kind.PACKUMENT);
        assertThat(p.packageName()).isEqualTo("lodash");
        assertThat(p.scope()).isNull();
        assertThat(p.unscopedName()).isEqualTo("lodash");
        assertThat(p.hasPackage()).isTrue();
    }

    @Test
    void scopedPackument() {
        NpmPath p = NpmPath.parse("@babel/core");
        assertThat(p.kind()).isEqualTo(Kind.PACKUMENT);
        assertThat(p.packageName()).isEqualTo("@babel/core");
        assertThat(p.scope()).isEqualTo("babel");
        assertThat(p.unscopedName()).isEqualTo("core");
    }

    @Test
    void doubleEncodedScopeSeparatorIsAccepted() {
        assertThat(NpmPath.parse("@babel%2Fcore").packageName()).isEqualTo("@babel/core");
        assertThat(NpmPath.parse("@babel%2fcore/-/core-7.0.0.tgz").kind()).isEqualTo(Kind.TARBALL);
        assertThat(NpmPath.parse("-/package/@babel%2Fcore/dist-tags").packageName()).isEqualTo("@babel/core");
    }

    @Test
    void leadingAndTrailingSlashesAreIgnored() {
        assertThat(NpmPath.parse("/lodash/").kind()).isEqualTo(Kind.PACKUMENT);
        assertThat(NpmPath.parse("/lodash/").packageName()).isEqualTo("lodash");
        assertThat(NpmPath.parse("/").kind()).isEqualTo(Kind.ROOT);
        assertThat(NpmPath.parse("").kind()).isEqualTo(Kind.ROOT);
        assertThat(NpmPath.parse(null).kind()).isEqualTo(Kind.ROOT);
    }

    @Test
    void versionOrDistTag() {
        NpmPath version = NpmPath.parse("lodash/4.17.21");
        assertThat(version.kind()).isEqualTo(Kind.VERSION);
        assertThat(version.packageName()).isEqualTo("lodash");
        assertThat(version.version()).isEqualTo("4.17.21");

        NpmPath tag = NpmPath.parse("@babel/core/latest");
        assertThat(tag.kind()).isEqualTo(Kind.VERSION);
        assertThat(tag.packageName()).isEqualTo("@babel/core");
        assertThat(tag.version()).isEqualTo("latest");
    }

    @Test
    void tarballs() {
        NpmPath plain = NpmPath.parse("lodash/-/lodash-4.17.21.tgz");
        assertThat(plain.kind()).isEqualTo(Kind.TARBALL);
        assertThat(plain.packageName()).isEqualTo("lodash");
        assertThat(plain.fileName()).isEqualTo("lodash-4.17.21.tgz");
        assertThat(plain.tarballPath()).isEqualTo("lodash/-/lodash-4.17.21.tgz");

        NpmPath scoped = NpmPath.parse("@babel/core/-/core-7.0.0.tgz");
        assertThat(scoped.kind()).isEqualTo(Kind.TARBALL);
        assertThat(scoped.packageName()).isEqualTo("@babel/core");
        assertThat(scoped.fileName()).isEqualTo("core-7.0.0.tgz");
        assertThat(scoped.tarballPath()).isEqualTo("@babel/core/-/core-7.0.0.tgz");
    }

    @Test
    void revisions() {
        NpmPath pkg = NpmPath.parse("lodash/-rev/3-abc");
        assertThat(pkg.kind()).isEqualTo(Kind.PACKAGE_REVISION);
        assertThat(pkg.packageName()).isEqualTo("lodash");
        assertThat(pkg.revision()).isEqualTo("3-abc");

        NpmPath tarball = NpmPath.parse("@acme/ui/-/ui-1.0.0.tgz/-rev/3-abc");
        assertThat(tarball.kind()).isEqualTo(Kind.TARBALL_REVISION);
        assertThat(tarball.packageName()).isEqualTo("@acme/ui");
        assertThat(tarball.fileName()).isEqualTo("ui-1.0.0.tgz");
        assertThat(tarball.revision()).isEqualTo("3-abc");
        assertThat(tarball.tarballPath()).isEqualTo("@acme/ui/-/ui-1.0.0.tgz");
    }

    @Test
    void distTags() {
        NpmPath all = NpmPath.parse("-/package/lodash/dist-tags");
        assertThat(all.kind()).isEqualTo(Kind.DIST_TAGS);
        assertThat(all.packageName()).isEqualTo("lodash");
        assertThat(all.tag()).isNull();

        NpmPath one = NpmPath.parse("-/package/@babel/core/dist-tags/beta");
        assertThat(one.kind()).isEqualTo(Kind.DIST_TAG);
        assertThat(one.packageName()).isEqualTo("@babel/core");
        assertThat(one.tag()).isEqualTo("beta");
    }

    @Test
    void registryLevelEndpoints() {
        NpmPath login = NpmPath.parse("-/user/org.couchdb.user:alice");
        assertThat(login.kind()).isEqualTo(Kind.LOGIN);
        assertThat(login.username()).isEqualTo("alice");
        assertThat(login.hasPackage()).isFalse();

        NpmPath loginRev = NpmPath.parse("-/user/org.couchdb.user:alice/-rev/1-x");
        assertThat(loginRev.kind()).isEqualTo(Kind.LOGIN);
        assertThat(loginRev.username()).isEqualTo("alice");
        assertThat(loginRev.revision()).isEqualTo("1-x");

        assertThat(NpmPath.parse("-/v1/login").kind()).isEqualTo(Kind.WEB_LOGIN);
        assertThat(NpmPath.parse("-/whoami").kind()).isEqualTo(Kind.WHOAMI);
        assertThat(NpmPath.parse("-/ping").kind()).isEqualTo(Kind.PING);
        assertThat(NpmPath.parse("-/v1/search").kind()).isEqualTo(Kind.SEARCH);
        assertThat(NpmPath.parse("-/npm/v1/security/advisories/bulk").kind()).isEqualTo(Kind.AUDIT);
        assertThat(NpmPath.parse("-/npm/v1/security/audits/quick").kind()).isEqualTo(Kind.AUDIT);
    }

    @Test
    void unknownPaths() {
        assertThat(NpmPath.parse("-").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(NpmPath.parse("-/foo").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(NpmPath.parse("-/user/alice").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(NpmPath.parse("-/user/org.couchdb.user:").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(NpmPath.parse("-/package/lodash").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(NpmPath.parse("-/package/lodash/dist-tags/a/b").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(NpmPath.parse("-/v1/other").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(NpmPath.parse("lodash/-").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(NpmPath.parse("lodash/-/a/b").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(NpmPath.parse("lodash/-rev").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(NpmPath.parse("lodash/1.0.0/extra").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(NpmPath.parse("lodash//1.0.0").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(NpmPath.parse("@scope").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(NpmPath.parse("../etc/passwd").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(NpmPath.parse(".hidden").kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void metadataVersusImmutableArtifacts() {
        assertThat(NpmPath.parse("lodash").isMetadata()).isTrue();
        assertThat(NpmPath.parse("@babel/core/latest").isMetadata()).isTrue();
        assertThat(NpmPath.parse("-/package/lodash/dist-tags").isMetadata()).isTrue();
        assertThat(NpmPath.parse("lodash/-/lodash-1.0.0.tgz").isMetadata()).isFalse();
        assertThat(NpmPath.parse("lodash/-/lodash-1.0.0.tgz/-rev/1-a").isMetadata()).isFalse();
    }

    @Test
    void packageNameValidation() {
        assertThat(NpmPath.isValidPackageName("lodash")).isTrue();
        assertThat(NpmPath.isValidPackageName("@babel/core")).isTrue();
        assertThat(NpmPath.isValidPackageName("JSONStream")).isTrue();
        assertThat(NpmPath.isValidPackageName("some_pkg.js")).isTrue();
        assertThat(NpmPath.isValidPackageName("@a")).isFalse();
        assertThat(NpmPath.isValidPackageName("a/b")).isFalse();
        assertThat(NpmPath.isValidPackageName("@a/b/c")).isFalse();
        assertThat(NpmPath.isValidPackageName(".x")).isFalse();
        assertThat(NpmPath.isValidPackageName("a b")).isFalse();
        assertThat(NpmPath.isValidPackageName("")).isFalse();
        assertThat(NpmPath.isValidPackageName(null)).isFalse();
    }

    @Test
    void staticNameHelpers() {
        assertThat(NpmPath.scopeOf("@acme/ui")).isEqualTo("acme");
        assertThat(NpmPath.scopeOf("ui")).isNull();
        assertThat(NpmPath.unscopedNameOf("@acme/ui")).isEqualTo("ui");
        assertThat(NpmPath.unscopedNameOf("ui")).isEqualTo("ui");
        assertThat(NpmPath.tarballPath("@acme/ui", "ui-1.0.0.tgz")).isEqualTo("@acme/ui/-/ui-1.0.0.tgz");
    }
}
