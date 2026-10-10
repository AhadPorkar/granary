package dev.granary.format.raw;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import java.util.List;

/**
 * Browser CDNs (cdnjs, jsDelivr, unpkg) proxied so that web pages built in an isolated network still load
 * their scripts and styles. Paths that pin an exact version are cached forever; unpinned paths (which the
 * CDNs answer with redirects to the current version) are re-validated after the metadata max age.
 */
@org.springframework.stereotype.Component
public class CdnFormatHandler extends RawFormatHandler {

    public static final String FORMAT = "cdn";

    /** cdnjs' library metadata API; everything else is decided by the version-pin rule. */
    static final List<String> DEFAULT_METADATA = List.of("libraries/**", "api/**", "libraries");

    public CdnFormatHandler(StorageService storage, ProxyService proxy, GroupResolver groups, GranaryProperties properties) {
        super(storage, proxy, groups, properties);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Browser CDN assets (cdnjs, jsDelivr, unpkg)";
    }

    @Override
    protected List<String> defaultMetadataPatterns() {
        return DEFAULT_METADATA;
    }

    @Override
    protected boolean isProfileMetadata(String path) {
        return !CdnPaths.isPinned(path);
    }

    @Override
    protected StorageService.ComponentCoordinates coordinates(String path) {
        return CdnPaths.coordinates(path);
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("cdnjs", FORMAT, "https://cdnjs.cloudflare.com/ajax/libs/", "Proxy of cdnjs (library/version/file)"),
                RepositoryTemplate.proxy("jsdelivr", FORMAT, "https://cdn.jsdelivr.net/", "Proxy of jsDelivr (npm/, gh/ and wp/ paths)"),
                RepositoryTemplate.proxy("unpkg", FORMAT, "https://unpkg.com/", "Proxy of unpkg (npm packages by name@version)"));
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext ctx) {
        String url = ctx.url();
        String remote = ctx.repository().getRemoteUrl() == null ? "" : ctx.repository().getRemoteUrl();
        String example;
        String esm;
        if (remote.contains("cdnjs")) {
            example = url + "/lodash.js/4.17.21/lodash.min.js";
            esm = url + "/lodash.js/4.17.21/lodash.min.js";
        } else if (remote.contains("unpkg")) {
            example = url + "/lodash@4.17.21/lodash.min.js";
            esm = url + "/lodash-es@4.17.21/lodash.js";
        } else {
            example = url + "/npm/lodash@4.17.21/lodash.min.js";
            esm = url + "/npm/lodash-es@4.17.21/+esm";
        }
        String note = ctx.anonymousRead() ? "" : "<!-- browsers cannot send API tokens: enable anonymous read for CDN repositories -->\n";
        String curlAuth = ctx.anonymousRead() ? "" : " -u " + ctx.username() + ":" + ctx.token();
        return List.of(
                ConnectSnippet.file("script-tag", "HTML: script and stylesheet tags", "text", "index.html",
                        note + "<script src=\"" + example + "\"></script>\n"
                                + "<link rel=\"stylesheet\" href=\"" + url + (remote.contains("cdnjs")
                                ? "/normalize/8.0.1/normalize.min.css" : remote.contains("unpkg")
                                ? "/normalize.css@8.0.1/normalize.css" : "/npm/normalize.css@8.0.1/normalize.css") + "\">"),
                ConnectSnippet.file("importmap", "HTML: import map for ES modules", "text", "index.html",
                        "<script type=\"importmap\">\n"
                                + "{\n"
                                + "  \"imports\": {\n"
                                + "    \"lodash-es\": \"" + esm + "\"\n"
                                + "  }\n"
                                + "}\n"
                                + "</script>\n"
                                + "<script type=\"module\">\n"
                                + "  import { chunk } from \"lodash-es\";\n"
                                + "</script>"),
                ConnectSnippet.file("vite", "Vite: serve a build from this repository", "text", "vite.config.js",
                        "// upload the dist/ folder with curl -T and set base to its URL\n"
                                + "export default {\n"
                                + "  base: \"" + url + "/my-app/1.0.0/\",\n"
                                + "};"),
                ConnectSnippet.file("webpack", "webpack: publicPath", "text", "webpack.config.js",
                        "module.exports = {\n"
                                + "  output: {\n"
                                + "    publicPath: \"" + url + "/my-app/1.0.0/\",\n"
                                + "  },\n"
                                + "};"),
                ConnectSnippet.shell("curl", "curl: warm the cache or compare with the CDN",
                        "curl -fsSL" + curlAuth + " -O " + example + "\n"
                                + "# unpinned URLs follow the CDN's redirect and are re-validated after the metadata max age:\n"
                                + "curl -fsSL" + curlAuth + " " + url + (remote.contains("unpkg") ? "/lodash/lodash.min.js" : remote.contains("cdnjs")
                                ? "/lodash.js/4.17.21/lodash.min.js" : "/npm/lodash/lodash.min.js") + " -o lodash.latest.js"));
    }
}
