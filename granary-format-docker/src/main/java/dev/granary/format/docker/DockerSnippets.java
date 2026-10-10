package dev.granary.format.docker;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.model.Repository;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "connect" instructions for the container tooling: every client talks to {@code host[:port]} and names the
 * image {@code <repository>/<image>} unless the repository is the registry root.
 */
final class DockerSnippets {

    private DockerSnippets() {
    }

    static List<ConnectSnippet> of(ConnectContext context) {
        Repository repo = context.repository();
        String host = context.host();
        boolean https = context.https();
        boolean root = repo.settingEnabled(DockerFormatHandler.ROOT_SETTING, false);
        String prefix = root ? "" : repo.getName() + "/";
        boolean pushable = repo.isHosted();
        String pullExample = pullExample(repo);
        String pullRef = host + "/" + prefix + pullExample;
        String pushRef = host + "/" + prefix + "acme/app:1.0";
        String user = context.username();
        String token = context.token();
        String tls = https ? "" : " --tls-verify=false";
        String plainHttp = https ? "" : " --plain-http";
        String scheme = https ? "https" : "http";

        List<ConnectSnippet> snippets = new ArrayList<>();
        snippets.add(ConnectSnippet.shell("docker-login", "docker login",
                "# docker only sends credentials after a login, also for pulls\n"
                        + "echo '" + token + "' | docker login " + host + " -u " + user + " --password-stdin\n"));
        snippets.add(ConnectSnippet.shell("docker-pull", "docker pull",
                "docker pull " + pullRef + "\n"));
        if (pushable) {
            snippets.add(ConnectSnippet.shell("docker-push", "docker push",
                    "docker tag acme/app:1.0 " + pushRef + "\n"
                            + "docker push " + pushRef + "\n"));
        }
        if (!https) {
            snippets.add(ConnectSnippet.file("docker-daemon", "docker: allow the plain-http registry (/etc/docker/daemon.json)",
                    "json", "daemon.json",
                    "{\n  \"insecure-registries\": [\"" + host + "\"]\n}\n"));
        }
        snippets.add(ConnectSnippet.file("podman-registries", "podman, buildah, skopeo: registries.conf", "toml",
                "/etc/containers/registries.conf.d/granary.conf",
                "[[registry]]\nlocation = \"" + host + "\"\n" + (https ? "" : "insecure = true\n")));
        snippets.add(ConnectSnippet.shell("podman", "podman",
                "echo '" + token + "' | podman login " + host + " -u " + user + " --password-stdin" + tls + "\n"
                        + "podman pull" + tls + " " + pullRef + "\n"
                        + (pushable ? "podman push" + tls + " acme/app:1.0 " + pushRef + "\n" : "")));
        snippets.add(ConnectSnippet.shell("skopeo", "skopeo",
                "echo '" + token + "' | skopeo login " + host + " -u " + user + " --password-stdin" + tls + "\n"
                        + "skopeo inspect" + tls + " docker://" + pullRef + "\n"
                        + (pushable
                        ? "skopeo copy --dest-tls-verify=" + https + " docker://docker.io/library/alpine:3.20 docker://" + host + "/" + prefix + "mirror/alpine:3.20\n"
                        : "")));
        if (pushable) {
            snippets.add(ConnectSnippet.shell("buildah", "buildah",
                    "echo '" + token + "' | buildah login " + host + " -u " + user + " --password-stdin" + tls + "\n"
                            + "buildah bud -t " + pushRef + " .\n"
                            + "buildah push" + tls + " " + pushRef + "\n"));
        }
        String insecure = https ? "" : " --insecure-registry";
        snippets.add(ConnectSnippet.shell("nerdctl", "nerdctl",
                "echo '" + token + "' | nerdctl login " + host + " -u " + user + " --password-stdin" + insecure + "\n"
                        + "nerdctl pull" + insecure + " " + pullRef + "\n"
                        + (pushable ? "nerdctl push" + insecure + " " + pushRef + "\n" : "")));
        snippets.add(ConnectSnippet.file("containerd-hosts", "containerd and kubelet: hosts.toml", "toml",
                "/etc/containerd/certs.d/" + host + "/hosts.toml",
                "server = \"" + scheme + "://" + host + "\"\n\n"
                        + "[host.\"" + scheme + "://" + host + "\"]\n"
                        + "  capabilities = [\"pull\", \"resolve\"" + (pushable ? ", \"push\"" : "") + "]\n"
                        + (https ? "" : "  skip_verify = true\n")));
        snippets.add(ConnectSnippet.shell("oras", "oras",
                "echo '" + token + "' | oras login " + host + " -u " + user + " --password-stdin" + plainHttp + "\n"
                        + (pushable
                        ? "oras push" + plainHttp + " " + host + "/" + prefix + "acme/artifacts:1.0 ./artifact.txt\n"
                        : "")
                        + "oras pull" + plainHttp + " " + host + "/" + prefix + "acme/artifacts:1.0\n"));
        snippets.add(ConnectSnippet.shell("helm", "helm (OCI charts)",
                "echo '" + token + "' | helm registry login " + host + " -u " + user + " --password-stdin"
                        + (https ? "" : " --insecure") + "\n"
                        + (pushable ? "helm push mychart-0.1.0.tgz oci://" + host + "/" + prefix + "charts" + plainHttp + "\n" : "")
                        + "helm pull oci://" + host + "/" + prefix + "charts/mychart --version 0.1.0" + plainHttp + "\n"));
        snippets.add(ConnectSnippet.shell("kubernetes-secret", "Kubernetes: image pull secret",
                "kubectl create secret docker-registry granary \\\n"
                        + "  --docker-server=" + host + " --docker-username=" + user + " --docker-password='" + token + "'\n"));
        snippets.add(ConnectSnippet.file("kubernetes-pod", "Kubernetes: pod using the registry", "yaml", "pod.yaml",
                "apiVersion: v1\nkind: Pod\nmetadata:\n  name: app\nspec:\n  imagePullSecrets:\n    - name: granary\n"
                        + "  containers:\n    - name: app\n      image: " + pullRef + "\n"));
        if (!root) {
            snippets.add(ConnectSnippet.shell("curl", "curl",
                    "curl -u " + user + ":" + token + " " + context.url() + "/v2/\n"
                            + "curl -u " + user + ":" + token + " " + context.url() + "/v2/_catalog\n"));
        }
        return snippets;
    }

    /** An image that exists in the repository's world: pushed by the user, or a public one of the proxied registry. */
    static String pullExample(Repository repo) {
        if (repo.isHosted()) {
            return "acme/app:1.0";
        }
        if (!repo.isProxy() || DockerUpstream.isDockerHub(repo)
                || repo.settingEnabled(DockerUpstream.LIBRARY_PREFIX_SETTING, false)) {
            return "library/alpine:3.20";
        }
        String host;
        try {
            host = repo.getRemoteUrl() == null ? "" : URI.create(repo.getRemoteUrl().trim()).getHost();
        } catch (IllegalArgumentException e) {
            host = "";
        }
        return switch (host == null ? "" : host.toLowerCase(Locale.ROOT)) {
            case "ghcr.io" -> "oras-project/oras:v1.2.0";
            case "quay.io" -> "prometheus/node-exporter:v1.8.2";
            case "registry.k8s.io" -> "pause:3.10";
            case "mcr.microsoft.com" -> "dotnet/runtime:8.0";
            default -> "<image>:<tag>";
        };
    }
}
