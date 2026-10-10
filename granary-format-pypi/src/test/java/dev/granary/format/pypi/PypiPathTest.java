package dev.granary.format.pypi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PypiPathTest {

    @Test
    void root() {
        PypiPath p = PypiPath.parse("");
        assertThat(p.kind()).isEqualTo(PypiPath.Kind.ROOT);
        assertThat(p.isMetadata()).isTrue();
        assertThat(PypiPath.parse(null).kind()).isEqualTo(PypiPath.Kind.ROOT);
        assertThat(PypiPath.parse("/").kind()).isEqualTo(PypiPath.Kind.ROOT);
    }

    @Test
    void uploadEndpoint() {
        assertThat(PypiPath.parse("legacy/").kind()).isEqualTo(PypiPath.Kind.UPLOAD);
        assertThat(PypiPath.parse("legacy").kind()).isEqualTo(PypiPath.Kind.UPLOAD);
        assertThat(PypiPath.parse("legacy/x").kind()).isEqualTo(PypiPath.Kind.UNKNOWN);
    }

    @Test
    void simpleIndex() {
        assertThat(PypiPath.parse("simple/").kind()).isEqualTo(PypiPath.Kind.SIMPLE_INDEX);
        assertThat(PypiPath.parse("simple/").isCanonical()).isTrue();
        assertThat(PypiPath.parse("simple/index.html").kind()).isEqualTo(PypiPath.Kind.SIMPLE_INDEX);
        PypiPath noSlash = PypiPath.parse("simple");
        assertThat(noSlash.kind()).isEqualTo(PypiPath.Kind.SIMPLE_INDEX);
        assertThat(noSlash.isCanonical()).isFalse();
        assertThat(noSlash.canonicalPath()).isEqualTo("simple/");
    }

    @Test
    void simpleProject() {
        PypiPath p = PypiPath.parse("simple/six/");
        assertThat(p.kind()).isEqualTo(PypiPath.Kind.SIMPLE_PROJECT);
        assertThat(p.project()).isEqualTo("six");
        assertThat(p.isCanonical()).isTrue();
        assertThat(p.isMetadata()).isTrue();
        assertThat(PypiPath.parse("simple/six/index.html").project()).isEqualTo("six");
        assertThat(PypiPath.parse("simple/six/index.html").isCanonical()).isFalse();
    }

    @Test
    void nonCanonicalProjectPathsRedirect() {
        for (String raw : new String[] {"simple/six", "simple/Six/", "simple/SIX", "simple/zope.interface/", "simple/Zope_Interface"}) {
            PypiPath p = PypiPath.parse(raw);
            assertThat(p.kind()).as(raw).isEqualTo(PypiPath.Kind.SIMPLE_PROJECT);
            assertThat(p.isCanonical()).as(raw).isFalse();
            assertThat(p.canonicalPath()).as(raw).endsWith("/");
        }
        assertThat(PypiPath.parse("simple/Zope_Interface").canonicalPath()).isEqualTo("simple/zope-interface/");
        assertThat(PypiPath.parse("simple/Zope_Interface").project()).isEqualTo("zope-interface");
    }

    @Test
    void packageFiles() {
        PypiPath hosted = PypiPath.parse("packages/acme-demo/1.0.0/acme_demo-1.0.0-py3-none-any.whl");
        assertThat(hosted.kind()).isEqualTo(PypiPath.Kind.PACKAGE_FILE);
        assertThat(hosted.filename()).isEqualTo("acme_demo-1.0.0-py3-none-any.whl");
        assertThat(hosted.hostedProject()).isEqualTo("acme-demo");
        assertThat(hosted.hostedVersion()).isEqualTo("1.0.0");
        assertThat(hosted.isMetadata()).isFalse();
        assertThat(hosted.isMetadataSidecar()).isFalse();

        PypiPath upstream = PypiPath.parse("packages/d9/5a/e7c31adbe875f2abbb91bd84cf2dc52d792b5a01506781dbcf25c91daf11/six-1.16.0-py2.py3-none-any.whl");
        assertThat(upstream.kind()).isEqualTo(PypiPath.Kind.PACKAGE_FILE);
        assertThat(upstream.hostedProject()).isNull();
        assertThat(upstream.filename()).isEqualTo("six-1.16.0-py2.py3-none-any.whl");

        PypiPath sidecar = PypiPath.parse("packages/acme-demo/1.0.0/acme_demo-1.0.0-py3-none-any.whl.metadata");
        assertThat(sidecar.isMetadataSidecar()).isTrue();
        assertThat(sidecar.isMetadata()).isFalse();

        PypiPath foreign = PypiPath.parse("packages/-/acme-demo/acme_demo-1.0.0.tar.gz");
        assertThat(foreign.kind()).isEqualTo(PypiPath.Kind.PACKAGE_FILE);
        assertThat(foreign.hostedProject()).isNull();
        assertThat(foreign.hostedVersion()).isNull();

        assertThat(PypiPath.parse("packages/source/s/six/six-1.16.0.tar.gz").kind()).isEqualTo(PypiPath.Kind.PACKAGE_FILE);
    }

    @Test
    void packageVersionForDeletes() {
        PypiPath p = PypiPath.parse("packages/Acme.Demo/1.0.0");
        assertThat(p.kind()).isEqualTo(PypiPath.Kind.PACKAGE_VERSION);
        assertThat(p.project()).isEqualTo("acme-demo");
        assertThat(p.version()).isEqualTo("1.0.0");
        assertThat(p.hostedProject()).isEqualTo("acme-demo");
        assertThat(p.hostedVersion()).isEqualTo("1.0.0");
        assertThat(PypiPath.parse("packages/six").kind()).isEqualTo(PypiPath.Kind.UNKNOWN);
        assertThat(PypiPath.parse("packages/six/1.0/").kind()).isEqualTo(PypiPath.Kind.UNKNOWN);
    }

    @Test
    void jsonApi() {
        PypiPath project = PypiPath.parse("pypi/Six/json");
        assertThat(project.kind()).isEqualTo(PypiPath.Kind.JSON_PROJECT);
        assertThat(project.project()).isEqualTo("six");
        assertThat(project.canonicalPath()).isEqualTo("pypi/six/json");
        assertThat(project.isMetadata()).isTrue();
        PypiPath release = PypiPath.parse("pypi/six/1.16.0/json");
        assertThat(release.kind()).isEqualTo(PypiPath.Kind.JSON_RELEASE);
        assertThat(release.version()).isEqualTo("1.16.0");
        assertThat(PypiPath.parse("pypi/six").kind()).isEqualTo(PypiPath.Kind.UNKNOWN);
        assertThat(PypiPath.parse("pypi/six/1.0/2.0/json").kind()).isEqualTo(PypiPath.Kind.UNKNOWN);
        assertThat(PypiPath.parse("pypi/six/").kind()).isEqualTo(PypiPath.Kind.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple/../etc/", "simple/./", "simple/a b/", "simple//", "packages/a/../b/c", "packages/a/b/.hidden",
            "packages/a//b", "packages/a/b/c\u0000", "pypi/../json", "pypi/a b/json", "simple/\\x/", "simple/a%2Fb/",
            "packages/..", "packages/-/x/..", "pypi/six/../json"})
    void oddPathsAreInvalidNotUnknown(String raw) {
        assertThat(PypiPath.parse(raw).kind()).as(raw).isEqualTo(PypiPath.Kind.INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"robots.txt", "simple/six/extra/", "foo/bar", "packages", "packages/", "pypi/", "stats/"})
    void otherPathsAreUnknown(String raw) {
        assertThat(PypiPath.parse(raw).kind()).as(raw).isEqualTo(PypiPath.Kind.UNKNOWN);
    }
}
