package dev.granary.format.pypi;

import java.util.Locale;

/**
 * PEP 691 content negotiation for the simple API: the client lists the representations it understands
 * in {@code Accept} (pip and uv prefer {@code application/vnd.pypi.simple.v1+json}, older clients send
 * {@code text/html} or nothing) and the server answers in the best one it has. The optional {@code format}
 * URL parameter overrides the header.
 *
 * @param json        whether the JSON representation was chosen
 * @param contentType the media type to answer with
 */
public record PypiAccept(boolean json, String contentType) {

    public static final String JSON_V1 = "application/vnd.pypi.simple.v1+json";
    public static final String HTML_V1 = "application/vnd.pypi.simple.v1+html";
    public static final String JSON_LATEST = "application/vnd.pypi.simple.latest+json";
    public static final String HTML_LATEST = "application/vnd.pypi.simple.latest+html";
    public static final String TEXT_HTML = "text/html";
    public static final String TEXT_HTML_UTF8 = "text/html;charset=UTF-8";

    /** The header sent upstream when the client wants JSON: pip's own, which falls back to HTML on old indexes. */
    public static final String UPSTREAM_ACCEPT_JSON = JSON_V1 + ", " + HTML_V1 + ";q=0.2, " + TEXT_HTML + ";q=0.01";
    /** The header sent upstream when the client wants HTML. */
    public static final String UPSTREAM_ACCEPT_HTML = HTML_V1 + ", " + TEXT_HTML + ";q=0.9, */*;q=0.1";

    public static final PypiAccept HTML = new PypiAccept(false, TEXT_HTML_UTF8);
    public static final PypiAccept JSON = new PypiAccept(true, JSON_V1);

    public static PypiAccept negotiate(String acceptHeader, String formatParameter) {
        if (formatParameter != null && !formatParameter.isBlank()) {
            String f = formatParameter.trim().toLowerCase(Locale.ROOT);
            if (f.equals(JSON_V1) || f.equals(JSON_LATEST) || f.equals("json")) {
                return JSON;
            }
            if (f.equals(HTML_V1) || f.equals(HTML_LATEST)) {
                return new PypiAccept(false, HTML_V1);
            }
            if (f.equals(TEXT_HTML) || f.equals("html")) {
                return HTML;
            }
        }
        double json = -1;
        double vndHtml = -1;
        double textHtml = -1;
        if (acceptHeader != null) {
            for (String range : acceptHeader.split(",")) {
                String[] parts = range.trim().split(";");
                String type = parts[0].trim().toLowerCase(Locale.ROOT);
                double q = quality(parts);
                switch (type) {
                    case JSON_V1, JSON_LATEST -> json = Math.max(json, q);
                    case HTML_V1, HTML_LATEST -> vndHtml = Math.max(vndHtml, q);
                    case TEXT_HTML -> textHtml = Math.max(textHtml, q);
                    default -> {
                        // wildcards and unknown types never decide; HTML is the safe default for them
                    }
                }
            }
        }
        double html = Math.max(vndHtml, textHtml);
        if (json > 0 && json > html) {
            return JSON;
        }
        if (vndHtml > 0 && vndHtml >= textHtml) {
            return new PypiAccept(false, HTML_V1);
        }
        return HTML;
    }

    private static double quality(String[] parts) {
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.startsWith("q=")) {
                try {
                    return Double.parseDouble(p.substring(2).trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 1.0;
    }

    /** The {@code Accept} header to send to an upstream index for this representation. */
    public String upstreamAccept() {
        return json ? UPSTREAM_ACCEPT_JSON : UPSTREAM_ACCEPT_HTML;
    }
}
