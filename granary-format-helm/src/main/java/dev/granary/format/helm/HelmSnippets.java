package dev.granary.format.helm;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import java.util.ArrayList;
import java.util.List;

/**
 * Ready-to-paste client configuration for Helm repositories: the helm CLI, the ChartMuseum API (curl and the
 * {@code helm cm-push} plugin), chart dependencies, helmfile, Flux, Argo CD and Artifact Hub. Upload snippets are
 * offered for hosted repositories only.
 */
final class HelmSnippets {

    static final String REPO_NAME = "granary";

    private HelmSnippets() {
    }

    static List<ConnectSnippet> of(ConnectContext c) {
        String url = c.url();
        String user = c.username();
        String token = c.token();
        boolean hosted = c.repository().isHosted();
        List<ConnectSnippet> snippets = new ArrayList<>();

        snippets.add(ConnectSnippet.shell("helm-repo-add", "helm: add the repository", """
                helm repo add %s %s --username %s --password %s
                helm repo update
                helm search repo %s/""".formatted(REPO_NAME, url, user, token, REPO_NAME)));
        snippets.add(ConnectSnippet.shell("helm-install", "helm: pull and install a chart", """
                helm pull %1$s/<chart> --version <version>
                helm install my-release %1$s/<chart> --version <version>
                helm upgrade --install my-release %1$s/<chart> --version <version>""".formatted(REPO_NAME)));
        snippets.add(ConnectSnippet.file("helm-dependency", "helm: chart dependency (Chart.yaml)", "yaml", "Chart.yaml", """
                dependencies:
                  - name: <chart>
                    version: "<version>"
                    repository: "%s"
                # with the repository added as above (for its credentials):
                #   helm dependency update""".formatted(url)));
        snippets.add(ConnectSnippet.file("helm-repositories-yaml", "helm: repositories.yaml (CI)", "yaml",
                "~/.config/helm/repositories.yaml", """
                apiVersion: ""
                generated: "0001-01-01T00:00:00Z"
                repositories:
                  - name: %s
                    url: %s
                    username: %s
                    password: %s
                # then: helm repo update""".formatted(REPO_NAME, url, user, token)));
        if (hosted) {
            snippets.add(ConnectSnippet.shell("helm-push-curl", "Upload a chart with curl (ChartMuseum API)", """
                    helm package .
                    curl -u %2$s:%3$s --data-binary "@<chart>-<version>.tgz" %1$s/api/charts
                    # with a provenance file (helm package --sign):
                    curl -u %2$s:%3$s -F "chart=@<chart>-<version>.tgz" -F "prov=@<chart>-<version>.tgz.prov" %1$s/api/charts
                    # delete a version:
                    curl -u %2$s:%3$s -X DELETE %1$s/api/charts/<chart>/<version>""".formatted(url, user, token)));
            snippets.add(ConnectSnippet.shell("helm-cm-push", "helm cm-push (ChartMuseum plugin, helm 3)", """
                    helm plugin install https://github.com/chartmuseum/helm-push
                    helm repo add %1$s %2$s --username %3$s --password %4$s
                    helm cm-push <chart>-<version>.tgz %1$s
                    # or push a chart directory: helm cm-push ./mychart %1$s""".formatted(REPO_NAME, url, user, token)));
            snippets.add(ConnectSnippet.shell("helm-push-put", "Upload with PUT (Nexus / Artifactory style)", """
                    curl -u %s:%s --upload-file <chart>-<version>.tgz %s/""".formatted(user, token, url)));
        }
        snippets.add(ConnectSnippet.file("helmfile", "helmfile", "yaml", "helmfile.yaml", """
                repositories:
                  - name: %1$s
                    url: %2$s
                    username: %3$s
                    password: %4$s

                releases:
                  - name: my-release
                    chart: %1$s/<chart>
                    version: <version>""".formatted(REPO_NAME, url, user, token)));
        snippets.add(ConnectSnippet.file("flux-helmrepository", "Flux HelmRepository", "yaml", "helmrepository.yaml", """
                apiVersion: source.toolkit.fluxcd.io/v1
                kind: HelmRepository
                metadata:
                  name: %1$s
                  namespace: flux-system
                spec:
                  interval: 10m
                  url: %2$s
                  secretRef:
                    name: %1$s-helm
                ---
                apiVersion: v1
                kind: Secret
                metadata:
                  name: %1$s-helm
                  namespace: flux-system
                stringData:
                  username: %3$s
                  password: %4$s""".formatted(REPO_NAME, url, user, token)));
        snippets.add(ConnectSnippet.file("argocd-repository", "Argo CD repository", "yaml", "repository-secret.yaml", """
                apiVersion: v1
                kind: Secret
                metadata:
                  name: %1$s-helm
                  namespace: argocd
                  labels:
                    argocd.argoproj.io/secret-type: repository
                stringData:
                  name: %1$s
                  type: helm
                  url: %2$s
                  username: %3$s
                  password: %4$s""".formatted(REPO_NAME, url, user, token)));
        snippets.add(ConnectSnippet.file("artifact-hub", "Artifact Hub", "text", null, """
                Artifact Hub > Control Panel > Add repository > Kind: Helm charts
                URL: %s
                Artifact Hub reads index.yaml and the charts anonymously, so the repository must be
                reachable from the internet and anonymous read access must be enabled.%s""".formatted(url,
                hosted ? """

                        Ownership claims and verified publisher: upload artifacthub-repo.yml with
                          curl -u %s:%s --upload-file artifacthub-repo.yml %s/artifacthub-repo.yml""".formatted(user, token, url)
                        : "")));
        return snippets;
    }
}
