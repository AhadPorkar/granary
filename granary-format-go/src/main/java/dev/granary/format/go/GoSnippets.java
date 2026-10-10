package dev.granary.format.go;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import java.util.ArrayList;
import java.util.List;

/** The client configuration shown on a Go repository's "Connect" page: the go command, .netrc, Docker and curl. */
final class GoSnippets {

    private GoSnippets() {
    }

    static List<ConnectSnippet> of(ConnectContext ctx, String hostedMember) {
        List<ConnectSnippet> snippets = new ArrayList<>();
        snippets.add(ConnectSnippet.shell("go-env", "go (GOPROXY)", goEnv(ctx)));
        if (!ctx.repository().isProxy()) {
            snippets.add(ConnectSnippet.shell("go-private", "go (private modules)", goPrivate(ctx, hostedMember)));
        }
        snippets.add(ConnectSnippet.file("netrc", ".netrc (credentials)", "text", "~/.netrc", netrc(ctx)));
        snippets.add(ConnectSnippet.file("dockerfile", "Dockerfile", "text", "Dockerfile", dockerfile(ctx)));
        if (ctx.repository().isHosted()) {
            snippets.add(ConnectSnippet.shell("curl-publish", "Publish with curl", curlPublish(ctx)));
        }
        return snippets;
    }

    private static String goEnv(ConnectContext ctx) {
        String sumdb = ctx.repository().isHosted()
                ? """
                # A hosted repository has no checksum database to pass through, so go asks sum.golang.org
                # directly for public modules; exclude the modules published here with GONOSUMDB.
                """
                : """
                # The checksum database is consulted through the repository as well (sumdb/sum.golang.org/…),
                # so verification keeps working on machines that cannot reach sum.golang.org directly.
                """;
        return """
                # Resolve modules through this repository. The trailing ",direct" is a fallback: when the
                # repository answers 404 or 410 for a module, go fetches it from version control itself;
                # drop it to make this repository the only source.
                go env -w GOPROXY=%s,direct
                %sgo env -w GOSUMDB=sum.golang.org
                """.formatted(ctx.url(), sumdb);
    }

    private static String goPrivate(ConnectContext ctx, String hostedMember) {
        String where = ctx.repository().isHosted() ? ctx.repository().getName()
                : hostedMember == null ? "the hosted member" : "the hosted member " + hostedMember;
        return """
                # Modules published to %s are unknown to sum.golang.org: exclude their path prefix
                # from checksum-database lookups (go.sum verification against this repository's files still runs).
                go env -w GONOSUMDB=example.com/acme
                # GOPRIVATE sets GONOSUMDB and GONOPROXY at once; the latter would bypass this repository,
                # so if you use GOPRIVATE keep the proxy for everything:
                go env -w GOPRIVATE=example.com/acme GONOPROXY=none
                # Let builds update go.mod/go.sum when a new private version is required:
                go env -w GOFLAGS=-mod=mod
                """.formatted(where);
    }

    private static String netrc(ConnectContext ctx) {
        String host = ctx.host();
        int colon = host.indexOf(':');
        String machine = colon > 0 ? host.substring(0, colon) : host;
        StringBuilder sb = new StringBuilder();
        sb.append("# Credentials for the go command (GOAUTH=netrc, the default) and for curl --netrc; chmod 600 this file.\n");
        sb.append(ctx.anonymousRead()
                ? "# Needed for publishing; downloads work anonymously on this server.\n"
                : "# Needed for downloads as well, because anonymous reads are off on this server.\n");
        if (colon > 0) {
            // the go command matches the machine against host:port, curl against the host name alone
            sb.append("machine ").append(host).append(" login ").append(ctx.username()).append(" password ").append(ctx.token()).append('\n');
        }
        sb.append("machine ").append(machine).append(" login ").append(ctx.username()).append(" password ").append(ctx.token()).append('\n');
        if (ctx.https()) {
            sb.append("# Instead of this file the credentials can also go into the URL:\n");
            sb.append("#   go env -w GOPROXY=").append(ctx.url().replaceFirst("^https://", "https://" + ctx.username() + ":" + ctx.token() + "@"))
                    .append(",direct\n");
        } else {
            sb.append("# The go command sends credentials only over HTTPS (from this file and from user:password@ URLs alike),\n");
            sb.append("# so with plain HTTP it can only download anonymously: serve Granary over TLS when anonymous reads are off.\n");
        }
        return sb.toString();
    }

    private static String dockerfile(ConnectContext ctx) {
        return """
                # Build stage that resolves every module through the repository
                FROM golang:1.24 AS build
                ENV GOPROXY=%s,direct
                ENV GOSUMDB=sum.golang.org
                # Private modules published to the hosted repository:
                # ENV GONOSUMDB=example.com/acme
                WORKDIR /src
                COPY go.mod go.sum ./
                RUN go mod download
                COPY . .
                RUN CGO_ENABLED=0 go build -o /out/app .
                """.formatted(ctx.url());
    }

    private static String curlPublish(ConnectContext ctx) {
        String auth = "-u " + ctx.username() + ":" + ctx.token();
        return """
                # A module zip has every file under <module>@<version>/ with go.mod at that root:
                mkdir -p example.com/acme/hello@v0.1.0 && cp go.mod *.go example.com/acme/hello@v0.1.0/
                zip -r hello-v0.1.0.zip example.com/acme/hello@v0.1.0
                # Upload it; .info (with the upload time) and .mod are derived from the zip.
                # Upper-case letters in module paths and versions are written as !x (github.com/!acme/!hello).
                curl %s -T hello-v0.1.0.zip %s/example.com/acme/hello/@v/v0.1.0.zip
                # Or publish the files the go command produced in GOMODCACHE/cache/download/<module>/@v/
                # (go mod download -json example.com/acme/hello@v0.1.0 prints their paths):
                curl %s -T "{v0.1.0.zip,v0.1.0.mod,v0.1.0.info}" %s/example.com/acme/hello/@v/
                # Remove a version:
                curl %s -X DELETE %s/example.com/acme/hello/@v/v0.1.0
                """.formatted(auth, ctx.url(), auth, ctx.url(), auth, ctx.url());
    }
}
