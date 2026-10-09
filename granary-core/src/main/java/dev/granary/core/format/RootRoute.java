package dev.granary.core.format;

/**
 * The repository and repository-relative path a server-root request maps to. Returned by
 * {@link FormatHandler#routeRoot(RootRequest)} for protocols whose clients insist on fixed, root-level
 * URLs ({@code /v2/…} for Docker, {@code /.well-known/terraform.json} for Terraform). The server forwards
 * such a request to {@code /repository/{repositoryName}/{path}} and the request carries the attribute
 * {@link #ATTRIBUTE} set to the original root path, so handlers can build matching {@code Location} URLs.
 *
 * @param repositoryName target repository
 * @param path           repository-relative path, no leading slash
 */
public record RootRoute(String repositoryName, String path) {

    /** Servlet request attribute holding the original root-relative path (without leading slash) of a routed request. */
    public static final String ATTRIBUTE = "dev.granary.rootPath";
}
