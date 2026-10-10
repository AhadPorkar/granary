package dev.granary.format.pypi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PypiAcceptTest {

    private static final String PIP = "application/vnd.pypi.simple.v1+json, application/vnd.pypi.simple.v1+html; q=0.1, text/html; q=0.01";
    private static final String UV = "application/vnd.pypi.simple.v1+json, application/vnd.pypi.simple.v1+html;q=0.2, text/html;q=0.01";

    @Test
    void pipAndUvGetJson() {
        for (String header : new String[] {PIP, UV}) {
            PypiAccept accept = PypiAccept.negotiate(header, null);
            assertThat(accept.json()).isTrue();
            assertThat(accept.contentType()).isEqualTo(PypiAccept.JSON_V1);
            assertThat(accept.upstreamAccept()).startsWith(PypiAccept.JSON_V1);
        }
    }

    @Test
    void oldClientsAndBrowsersGetHtml() {
        assertThat(PypiAccept.negotiate(null, null)).isEqualTo(PypiAccept.HTML);
        assertThat(PypiAccept.negotiate("*/*", null)).isEqualTo(PypiAccept.HTML);
        assertThat(PypiAccept.negotiate("text/html", null)).isEqualTo(PypiAccept.HTML);
        assertThat(PypiAccept.negotiate("text/html,application/xhtml+xml,*/*;q=0.8", null)).isEqualTo(PypiAccept.HTML);
        assertThat(PypiAccept.negotiate("application/json", null)).isEqualTo(PypiAccept.HTML);
        assertThat(PypiAccept.negotiate("", null).json()).isFalse();
        assertThat(PypiAccept.HTML.upstreamAccept()).startsWith(PypiAccept.HTML_V1);
    }

    @Test
    void explicitVndHtmlGetsThatContentType() {
        PypiAccept accept = PypiAccept.negotiate("application/vnd.pypi.simple.v1+html", null);
        assertThat(accept.json()).isFalse();
        assertThat(accept.contentType()).isEqualTo(PypiAccept.HTML_V1);
        assertThat(PypiAccept.negotiate("text/html, application/vnd.pypi.simple.v1+html;q=0.5", null).contentType())
                .isEqualTo(PypiAccept.TEXT_HTML_UTF8);
    }

    @Test
    void qualityDecidesAndTiesGoToHtml() {
        assertThat(PypiAccept.negotiate("text/html, application/vnd.pypi.simple.v1+json;q=0.5", null).json()).isFalse();
        assertThat(PypiAccept.negotiate("text/html;q=0.5, application/vnd.pypi.simple.v1+json;q=0.5", null).json()).isFalse();
        assertThat(PypiAccept.negotiate("text/html;q=0.4, application/vnd.pypi.simple.v1+json;q=0.5", null).json()).isTrue();
        assertThat(PypiAccept.negotiate("application/vnd.pypi.simple.v1+json;q=0", null).json()).isFalse();
        assertThat(PypiAccept.negotiate("application/vnd.pypi.simple.v1+json;q=abc", null).json()).isFalse();
        assertThat(PypiAccept.negotiate("application/vnd.pypi.simple.latest+json", null).json()).isTrue();
        assertThat(PypiAccept.negotiate("APPLICATION/VND.PYPI.SIMPLE.V1+JSON", null).json()).isTrue();
    }

    @Test
    void formatParameterOverridesTheHeader() {
        assertThat(PypiAccept.negotiate("text/html", "application/vnd.pypi.simple.v1+json").json()).isTrue();
        assertThat(PypiAccept.negotiate(PIP, "text/html").json()).isFalse();
        assertThat(PypiAccept.negotiate(PIP, "application/vnd.pypi.simple.v1+html").contentType()).isEqualTo(PypiAccept.HTML_V1);
        assertThat(PypiAccept.negotiate(PIP, "json").json()).isTrue();
        assertThat(PypiAccept.negotiate(PIP, "nonsense").json()).isTrue();
        assertThat(PypiAccept.negotiate(PIP, " ").json()).isTrue();
    }
}
