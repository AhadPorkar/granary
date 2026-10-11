package dev.granary.core.proxy;

import dev.granary.core.GranaryException;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where the upstream credentials of a proxy repository ({@code remoteUsername}/{@code remotePassword} or
 * {@code remoteToken}) may be sent.
 *
 * <p>Upstream documents name other hosts all the time: chart archives on GitHub releases, provider zips on
 * releases.hashicorp.com, crates on a CDN, wheels on files.pythonhosted.org, NuGet search on another domain.
 * The credentials belong to the upstream alone, so the rule is the browser's: they go to the <em>origin</em>
 * (scheme, host and port) of the repository's remote URL and nowhere else. The only way to widen that is the
 * repository setting {@value #HOSTS_SETTING}, a list of further hosts an administrator trusts with them (an
 * internal mirror whose downloads live on a second internal host). Plain HTTP never carries them when the remote
 * URL is HTTPS, whatever the list says.
 *
 * <p>Hosts are compared in their ASCII form: lower case, internationalised names as punycode, one trailing dot
 * (the fully qualified form) ignored, IP addresses in their canonical form. A URL whose host cannot be read that
 * way never matches anything.
 */
public final class UpstreamCredentials {

    /** Repository setting: further hosts that receive the upstream credentials. */
    public static final String HOSTS_SETTING = "upstream.credentialHosts";

    /** How {@link #HOSTS_SETTING} is offered for the proxy repositories of every format. */
    public static final SettingDescriptor HOSTS_DESCRIPTOR = SettingDescriptor.string(HOSTS_SETTING,
            "Additional hosts for upstream credentials", null,
            "Comma-separated hosts that receive this proxy's upstream credentials besides the remote URL's own "
                    + "scheme, host and port: host, host:port (a bare host means the default port) or *.example.com "
                    + "for its subdomains. No other host ever receives them, and plain HTTP never does when the remote "
                    + "URL uses HTTPS.")
            .forTypes(RepositoryType.PROXY);

    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");

    private UpstreamCredentials() {
    }

    /** The scheme, host and port of a URL, normalised so that equal origins compare equal. */
    public record Origin(String scheme, String host, int port) {

        /** The origin of an absolute http(s) URL, or empty when it has none that can be compared safely. */
        public static Optional<Origin> of(URI uri) {
            if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
                return Optional.empty();
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return Optional.empty();
            }
            String host = canonicalHost(uri.getHost());
            if (host == null) {
                return Optional.empty();
            }
            int port = uri.getPort() >= 0 ? uri.getPort() : defaultPort(scheme);
            return Optional.of(new Origin(scheme, host, port));
        }

        /** Like {@link #of(URI)} for a URL given as text; empty when it is not a valid URI. */
        public static Optional<Origin> of(String url) {
            if (url == null || url.isBlank()) {
                return Optional.empty();
            }
            try {
                return of(new URI(url.trim()));
            } catch (java.net.URISyntaxException e) {
                return Optional.empty();
            }
        }

        public boolean secure() {
            return scheme.equals("https");
        }
    }

    /**
     * One entry of {@link #HOSTS_SETTING}: an exact host, or every subdomain of {@code suffix} when
     * {@code wildcard}; {@code port} is {@code -1} for the default port of the URL's scheme.
     */
    public record HostPattern(String host, boolean wildcard, int port) {

        public boolean matches(Origin origin) {
            int wanted = port >= 0 ? port : defaultPort(origin.scheme());
            if (origin.port() != wanted) {
                return false;
            }
            return wildcard
                    ? origin.host().endsWith("." + host) && origin.host().length() > host.length() + 1
                    : origin.host().equals(host);
        }
    }

    /** Whether the repository has upstream credentials at all. */
    public static boolean present(Repository repo) {
        return notBlank(repo.getRemoteToken()) || notBlank(repo.getRemoteUsername());
    }

    /**
     * The default rule: whether a request to {@code target} may carry the repository's upstream credentials. True
     * for the origin of the remote URL and for the hosts of {@link #HOSTS_SETTING}, never for plain HTTP when the
     * remote URL is HTTPS, false for anything that cannot be parsed.
     */
    public static boolean permitted(Repository repo, URI target) {
        Optional<Origin> remote = Origin.of(repo.getRemoteUrl());
        Optional<Origin> to = Origin.of(target);
        if (remote.isEmpty() || to.isEmpty() || downgrade(remote.get(), to.get())) {
            return false;
        }
        if (remote.get().equals(to.get())) {
            return true;
        }
        for (HostPattern pattern : trustedHosts(repo)) {
            if (pattern.matches(to.get())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code target} may carry the credentials of a request that the upstream protocol itself sent to
     * {@code named} (a container registry's token realm): only that host's own origin, and never plain HTTP when
     * the remote URL is HTTPS.
     */
    public static boolean permittedAtNamedHost(Repository repo, URI named, URI target) {
        Optional<Origin> remote = Origin.of(repo.getRemoteUrl());
        Optional<Origin> authority = Origin.of(named);
        Optional<Origin> to = Origin.of(target);
        if (remote.isEmpty() || authority.isEmpty() || to.isEmpty() || downgrade(remote.get(), to.get())) {
            return false;
        }
        return authority.get().equals(to.get());
    }

    /** Whether two URLs share scheme, host and port; false when either has no comparable origin. */
    public static boolean sameOrigin(URI a, URI b) {
        Optional<Origin> first = Origin.of(a);
        return first.isPresent() && first.equals(Origin.of(b));
    }

    /** The parsed {@link #HOSTS_SETTING} of a repository; entries that are not valid are ignored. */
    public static List<HostPattern> trustedHosts(Repository repo) {
        Optional<String> value = repo.setting(HOSTS_SETTING);
        if (value.isEmpty()) {
            return List.of();
        }
        List<HostPattern> patterns = new ArrayList<>();
        for (String entry : value.get().split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                patterns.add(parse(entry.trim()));
            } catch (IllegalArgumentException ignored) {
                // validation refuses such values; one stored earlier simply grants nothing
            }
        }
        return List.copyOf(patterns);
    }

    /** Refuses a {@link #HOSTS_SETTING} value that is not a list of hosts, before the repository is saved. */
    public static void validate(Repository repo) {
        Optional<String> value = repo.setting(HOSTS_SETTING);
        if (value.isEmpty()) {
            return;
        }
        for (String entry : value.get().split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                parse(entry.trim());
            } catch (IllegalArgumentException e) {
                throw GranaryException.badRequest("repository.upstream.credentialHosts.invalid",
                        HOSTS_SETTING + ": '" + entry.trim() + "' " + e.getMessage(),
                        Map.of("field", HOSTS_SETTING));
            }
        }
    }

    /**
     * Parses one entry: {@code host}, {@code host:port}, {@code [ipv6]}, {@code [ipv6]:port} or
     * {@code *.domain[:port]}. The message of the exception completes the sentence "'entry' ...".
     */
    public static HostPattern parse(String entry) {
        String text = entry.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("is empty");
        }
        if (text.contains("://") || text.contains("/") || text.contains("@") || text.contains("?") || text.contains("#")
                || text.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("must be a host or host:port, without scheme, user or path");
        }
        String host;
        int port = -1;
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("has no closing ']'");
            }
            host = text.substring(0, close + 1);
            String rest = text.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) {
                    throw new IllegalArgumentException("has text after the IPv6 address");
                }
                port = port(rest.substring(1));
            }
        } else {
            int colon = text.indexOf(':');
            if (colon >= 0) {
                if (text.indexOf(':', colon + 1) >= 0) {
                    throw new IllegalArgumentException("is an IPv6 address without brackets; write it as [address]");
                }
                host = text.substring(0, colon);
                port = port(text.substring(colon + 1));
            } else {
                host = text;
            }
        }
        boolean wildcard = host.startsWith("*.");
        if (wildcard) {
            host = host.substring(2);
        }
        if (host.contains("*")) {
            throw new IllegalArgumentException("may only use * as its first label (*.example.com)");
        }
        String canonical = canonicalHost(host);
        if (canonical == null) {
            throw new IllegalArgumentException("is not a valid host name or IP address");
        }
        if (!unambiguous(host, canonical)) {
            throw new IllegalArgumentException("is an internationalised name whose ASCII form is ambiguous (it would "
                    + "match " + canonical + "); write the host in its xn-- form");
        }
        if (wildcard && (isIpLiteral(canonical) || !canonical.contains("."))) {
            throw new IllegalArgumentException("must name a domain with at least two labels after *.");
        }
        return new HostPattern(canonical, wildcard, port);
    }

    /**
     * A host in the form origins are compared in: lower case ASCII (punycode for internationalised names), without
     * the trailing dot of a fully qualified name, IPv6 without brackets and IP addresses in canonical form; null when
     * it is not a valid host.
     */
    static String canonicalHost(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String host = raw;
        if (host.startsWith("[") && host.endsWith("]")) {
            String literal = host.substring(1, host.length() - 1);
            if (literal.isEmpty() || !literal.contains(":") || !literal.matches("[0-9A-Fa-f:.]+")) {
                return null;
            }
            try {
                return InetAddress.getByName(literal).getHostAddress().toLowerCase(Locale.ROOT);
            } catch (UnknownHostException e) {
                return null;
            }
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || host.startsWith(".") || host.contains("..")) {
            return null;
        }
        String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (ascii.isEmpty() || ascii.length() > 253) {
            return null;
        }
        if (ascii.matches("[0-9.]+")) {
            // only the dotted-quad form counts as an IPv4 address; shorthand forms such as 127.1 never match
            return ascii.matches("(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}")
                    ? ascii : null;
        }
        return ascii;
    }

    /**
     * Whether an internationalised host an administrator typed means exactly the ASCII host it is compared as. The
     * JDK converts names by IDNA 2003, which maps some characters to others ({@code ß} to {@code ss}, a final sigma to
     * sigma) and drops others (zero-width joiners) where today's resolvers, which follow IDNA 2008, keep them: "faß.de"
     * would grant the credentials to fass.de, another domain. Such a name must be written in its xn-- form.
     */
    private static boolean unambiguous(String typed, String canonical) {
        if (typed.chars().allMatch(c -> c < 0x80)) {
            return true;
        }
        String name = Normalizer.normalize(typed.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        return IDN.toUnicode(canonical, IDN.USE_STD3_ASCII_RULES).equals(name);
    }

    private static boolean isIpLiteral(String canonical) {
        return canonical.contains(":") || canonical.matches("[0-9.]+");
    }

    /** Credentials never travel over plain HTTP when the upstream itself is reached over HTTPS. */
    private static boolean downgrade(Origin remote, Origin target) {
        return remote.secure() && !target.secure();
    }

    private static int port(String text) {
        if (!PORT.matcher(text).matches()) {
            throw new IllegalArgumentException("has an invalid port");
        }
        int port = Integer.parseInt(text);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("has a port outside 1-65535");
        }
        return port;
    }

    static int defaultPort(String scheme) {
        return scheme.equals("https") ? 443 : 80;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
