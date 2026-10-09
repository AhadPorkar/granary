package dev.granary.core.format;

import dev.granary.core.model.Repository;

/**
 * What a format needs to render its {@link ConnectSnippet}s.
 *
 * @param repository    the repository being connected to
 * @param url           its absolute URL without a trailing slash, e.g. {@code https://host/repository/pypi-group}
 * @param serverUrl     the server root without a trailing slash, e.g. {@code https://host}
 * @param username      the caller's username, or a placeholder such as {@code <username>} when anonymous
 * @param token         an API token placeholder (never a real secret), e.g. {@code <api-token>}
 * @param anonymousRead whether unauthenticated downloads are allowed on this server
 */
public record ConnectContext(Repository repository, String url, String serverUrl, String username, String token,
                             boolean anonymousRead) {

    public static final String USERNAME_PLACEHOLDER = "<username>";
    public static final String TOKEN_PLACEHOLDER = "<api-token>";

    public String urlWithSlash() {
        return url + "/";
    }

    /** The URL without its scheme, the form npm and some other tools key credentials by. */
    public String urlWithoutScheme() {
        return url.replaceFirst("^https?:", "");
    }

    public String host() {
        return serverUrl.replaceFirst("^https?://", "");
    }

    public boolean https() {
        return serverUrl.startsWith("https://");
    }
}
