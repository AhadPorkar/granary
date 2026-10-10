package dev.granary.format.terraform;

import static dev.granary.format.terraform.TerraformTestSupport.MAPPER;
import static dev.granary.format.terraform.TerraformTestSupport.str;
import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.terraform.TerraformDocuments.MirrorArchive;
import dev.granary.format.terraform.TerraformDocuments.Platform;
import dev.granary.format.terraform.TerraformDocuments.ProviderVersion;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class TerraformDocumentsTest {

    private static final String BASE = "https://granary.test/repository/tf";
    private final TerraformDocuments docs = new TerraformDocuments(MAPPER);

    @Test
    void discoveryDocumentNamesBothServices() {
        ObjectNode doc = docs.discovery(BASE);
        assertThat(str(doc, "providers.v1")).isEqualTo(BASE + "/v1/providers/");
        assertThat(str(doc, "modules.v1")).isEqualTo(BASE + "/v1/modules/");
    }

    @Test
    void providerVersionsRoundTrip() {
        ProviderVersion v = new ProviderVersion("1.2.3", List.of("5.0", "6.0"),
                List.of(new Platform("linux", "amd64"), new Platform("darwin", "arm64")));
        ObjectNode doc = docs.providerVersions(List.of(v));
        assertThat(doc.path("versions")).hasSize(1);
        assertThat(str(doc.path("versions").get(0), "version")).isEqualTo("1.2.3");
        assertThat(doc.path("versions").get(0).path("protocols")).extracting(JsonNode::stringValue).containsExactly("5.0", "6.0");
        assertThat(doc.path("versions").get(0).path("platforms").get(1).path("arch").stringValue()).isEqualTo("arm64");
        assertThat(docs.parseProviderVersions(doc)).containsExactly(v);
    }

    @Test
    void parsingIsLenient() {
        JsonNode upstream = MAPPER.readTree("""
                {"id":"hashicorp/null","versions":[
                  {"version":"3.2.4","protocols":["5.0"],"platforms":[{"os":"linux","arch":"amd64"},{"os":"linux"},"junk"]},
                  {"version":"not-a-version","protocols":["5.0"],"platforms":[]},
                  {"protocols":["5.0"]},
                  {"version":"0.1.0","protocols":[4,"4.0",""],"platforms":"nope"}
                ],"warnings":null}""");
        List<ProviderVersion> parsed = docs.parseProviderVersions(upstream);
        assertThat(parsed).extracting(ProviderVersion::version).containsExactly("3.2.4", "0.1.0");
        assertThat(parsed.get(0).platforms()).containsExactly(new Platform("linux", "amd64"));
        assertThat(parsed.get(1).protocols()).containsExactly("4.0");
        assertThat(parsed.get(1).platforms()).isEmpty();
        assertThat(docs.parseProviderVersions(null)).isEmpty();
        assertThat(docs.parseProviderVersions(MAPPER.readTree("[]"))).isEmpty();
        assertThat(docs.parseProviderVersions(MAPPER.readTree("{\"versions\":\"x\"}"))).isEmpty();
    }

    @Test
    void mergeUnionsVersionsAndTheFirstMemberThatHasAVersionOwnsIt() {
        List<ProviderVersion> hosted = List.of(
                new ProviderVersion("1.0.0", List.of("5.0"), List.of(new Platform("linux", "amd64"))),
                new ProviderVersion("2.0.0", List.of("6.0"), List.of(new Platform("linux", "amd64"))));
        List<ProviderVersion> proxied = List.of(
                new ProviderVersion("v1.0.0", List.of("4.0", "5.0"), List.of(new Platform("darwin", "arm64"), new Platform("linux", "amd64"))),
                new ProviderVersion("0.5.0", List.of("4.0"), List.of(new Platform("linux", "386"))),
                new ProviderVersion("2.1.0-beta.1", List.of("6.0"), List.of()));

        List<ProviderVersion> merged = docs.mergeProviderVersions(List.of(hosted, proxied));

        assertThat(merged).extracting(ProviderVersion::version).containsExactly("0.5.0", "1.0.0", "2.0.0", "2.1.0-beta.1");
        // 1.0.0 belongs to the hosted member: its protocols and platforms only, nothing borrowed from the proxy's
        // build of the same version (whose SHA256SUMS and signature would not match the hosted ones)
        ProviderVersion one = merged.get(1);
        assertThat(one.protocols()).containsExactly("5.0");
        assertThat(one.platforms()).containsExactly(new Platform("linux", "amd64"));
        assertThat(merged.get(0).platforms()).containsExactly(new Platform("linux", "386"));
        assertThat(docs.mergeProviderVersions(List.of())).isEmpty();
        assertThat(docs.mergeProviderVersions(List.of(List.of(), proxied))).extracting(ProviderVersion::version)
                .containsExactly("0.5.0", "1.0.0", "2.1.0-beta.1");
    }

    @Test
    void sortAndLatest() {
        assertThat(TerraformDocuments.sortVersions(List.of("2.0.0", "v1.0.0", "1.0.0", "1.0.0-rc.1", "", "10.0.0")))
                .containsExactly("1.0.0-rc.1", "1.0.0", "2.0.0", "10.0.0");
        assertThat(TerraformDocuments.latest(List.of("1.0.0", "2.0.0-beta.1", "1.5.0"))).contains("1.5.0");
        assertThat(TerraformDocuments.latest(List.of("2.0.0-beta.1", "2.0.0-alpha"))).contains("2.0.0-beta.1");
        assertThat(TerraformDocuments.latest(List.of("junk"))).isEqualTo(Optional.empty());
        assertThat(TerraformDocuments.latest(List.of())).isEmpty();
    }

    @Test
    void hostedDownloadDocumentPointsAtTheRepository() {
        JsonNode keys = MAPPER.readTree("{\"gpg_public_keys\":[{\"key_id\":\"ABCD\",\"ascii_armor\":\"-----BEGIN PGP PUBLIC KEY BLOCK-----\"}]}");
        ObjectNode doc = docs.providerDownload(BASE, "acme", "demo", "1.0.0", "linux", "amd64",
                "terraform-provider-demo_1.0.0_linux_amd64.zip", "ab".repeat(32), List.of("5.0", "6.0"), keys);
        assertThat(doc.path("protocols")).extracting(JsonNode::stringValue).containsExactly("5.0", "6.0");
        assertThat(str(doc, "os")).isEqualTo("linux");
        assertThat(str(doc, "arch")).isEqualTo("amd64");
        assertThat(str(doc, "filename")).isEqualTo("terraform-provider-demo_1.0.0_linux_amd64.zip");
        assertThat(str(doc, "download_url")).isEqualTo(BASE + "/files/acme/demo/1.0.0/terraform-provider-demo_1.0.0_linux_amd64.zip");
        assertThat(str(doc, "shasums_url")).isEqualTo(BASE + "/files/acme/demo/1.0.0/SHA256SUMS");
        assertThat(str(doc, "shasums_signature_url")).isEqualTo(BASE + "/files/acme/demo/1.0.0/SHA256SUMS.sig");
        assertThat(str(doc, "shasum")).isEqualTo("ab".repeat(32));
        assertThat(doc.path("signing_keys").path("gpg_public_keys").get(0).path("key_id").stringValue()).isEqualTo("ABCD");

        ObjectNode noKeys = docs.providerDownload(BASE, "acme", "demo", "1.0.0", "linux", "amd64", "f.zip", "x", List.of(), null);
        assertThat(noKeys.path("signing_keys").path("gpg_public_keys").isArray()).isTrue();
        assertThat(noKeys.path("signing_keys").path("gpg_public_keys")).isEmpty();
    }

    @Test
    void upstreamDownloadDocumentIsRewrittenButSigningKeysPassThrough() {
        JsonNode upstream = MAPPER.readTree("""
                {"protocols":["5.0"],"os":"linux","arch":"amd64","filename":"terraform-provider-null_3.2.4_linux_amd64.zip",
                 "download_url":"https://releases.hashicorp.com/terraform-provider-null/3.2.4/terraform-provider-null_3.2.4_linux_amd64.zip",
                 "shasums_url":"https://releases.hashicorp.com/terraform-provider-null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS",
                 "shasums_signature_url":"https://releases.hashicorp.com/terraform-provider-null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS.sig?x=1",
                 "shasum":"abc","signing_keys":{"gpg_public_keys":[{"key_id":"34365D9472D7468F","ascii_armor":"-----BEGIN PGP PUBLIC KEY BLOCK-----","trust_signature":"","source":"HashiCorp","source_url":"https://www.hashicorp.com/security.html"}]}}""");

        ObjectNode doc = docs.rewriteProviderDownload(upstream, BASE, "hashicorp", "null", "3.2.4");

        assertThat(str(doc, "download_url")).isEqualTo(BASE + "/files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_linux_amd64.zip");
        assertThat(str(doc, "shasums_url")).isEqualTo(BASE + "/files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS");
        assertThat(str(doc, "shasums_signature_url")).isEqualTo(BASE + "/files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS.sig");
        assertThat(doc.path("signing_keys")).isEqualTo(upstream.path("signing_keys"));
        assertThat(str(doc, "shasum")).isEqualTo("abc");
        assertThat(str(upstream, "download_url")).startsWith("https://releases.hashicorp.com"); // the input is not mutated

        assertThat(TerraformDocuments.upstreamUrlFor(upstream, "terraform-provider-null_3.2.4_SHA256SUMS"))
                .contains("https://releases.hashicorp.com/terraform-provider-null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS");
        assertThat(TerraformDocuments.upstreamUrlFor(upstream, "terraform-provider-null_3.2.4_SHA256SUMS.sig"))
                .contains("https://releases.hashicorp.com/terraform-provider-null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS.sig?x=1");
        assertThat(TerraformDocuments.upstreamUrlFor(upstream, "other.zip")).isEmpty();
    }

    @Test
    void rewriteCopesWithMissingOrOddFields() {
        JsonNode upstream = MAPPER.readTree("{\"download_url\":\"https://x.example/a/b/c.zip\",\"shasums_url\":\"\",\"shasum\":1}");
        ObjectNode doc = docs.rewriteProviderDownload(upstream, BASE, "a", "b", "1.0.0");
        assertThat(str(doc, "download_url")).isEqualTo(BASE + "/files/a/b/1.0.0/c.zip");
        assertThat(str(doc, "filename")).isEqualTo("c.zip");
        assertThat(str(doc, "shasums_url")).isEmpty();
        assertThat(docs.rewriteProviderDownload(MAPPER.readTree("[1,2]"), BASE, "a", "b", "1.0.0").isEmpty()).isTrue();
        JsonNode traversal = MAPPER.readTree("{\"download_url\":\"https://x.example/a/..%2F..%2Fetc\"}");
        assertThat(str(docs.rewriteProviderDownload(traversal, BASE, "a", "b", "1.0.0"), "download_url")).startsWith("https://x.example");
    }

    @Test
    void moduleDocuments() {
        ObjectNode versions = docs.moduleVersions("acme", "demo", "local", List.of("1.0.0", "1.1.0"));
        assertThat(str(versions.path("modules").get(0), "source")).isEqualTo("acme/demo/local");
        assertThat(versions.path("modules").get(0).path("versions")).extracting(v -> v.path("version").stringValue())
                .containsExactly("1.0.0", "1.1.0");
        assertThat(docs.parseModuleVersions(versions)).containsExactly("1.0.0", "1.1.0");
        assertThat(docs.parseModuleVersions(MAPPER.readTree("{\"modules\":[{\"versions\":[{\"version\":\"x\"},{\"version\":\"2.0.0\",\"root\":{}}]},{\"versions\":[{\"version\":\"1.0.0\"}]}]}")))
                .containsExactly("2.0.0", "1.0.0");
        assertThat(docs.parseModuleVersions(null)).isEmpty();

        ObjectNode info = docs.moduleInfo("acme", "demo", "local", "1.1.0", List.of("1.0.0", "1.1.0"));
        assertThat(str(info, "id")).isEqualTo("acme/demo/local/1.1.0");
        assertThat(str(info, "provider")).isEqualTo("local");
        assertThat(info.path("versions")).hasSize(2);

        ObjectNode provider = docs.providerInfo("acme", "demo", "1.0.0", List.of("1.0.0"), List.of(new Platform("linux", "amd64")));
        assertThat(str(provider, "id")).isEqualTo("acme/demo/1.0.0");
        assertThat(provider.path("platforms").get(0).path("os").stringValue()).isEqualTo("linux");
    }

    @Test
    void mirrorDocuments() {
        ObjectNode index = docs.mirrorIndex(List.of("2.0.0", "1.0.0", "1.0.0"));
        assertThat(index.path("versions").propertyNames()).containsExactly("1.0.0", "2.0.0");
        assertThat(index.path("versions").path("1.0.0").isObject()).isTrue();

        ObjectNode version = docs.mirrorVersion(Map.of(
                "linux_amd64", new MirrorArchive(BASE + "/files/a/b/1.0.0/x.zip", List.of("zh:abc")),
                "darwin_arm64", new MirrorArchive(BASE + "/files/a/b/1.0.0/y.zip", List.of())));
        assertThat(version.path("archives").propertyNames()).containsExactly("darwin_arm64", "linux_amd64");
        assertThat(str(version.path("archives").path("linux_amd64"), "url")).isEqualTo(BASE + "/files/a/b/1.0.0/x.zip");
        assertThat(version.path("archives").path("linux_amd64").path("hashes").get(0).stringValue()).isEqualTo("zh:abc");
        assertThat(version.path("archives").path("darwin_arm64").has("hashes")).isFalse();
        assertThat(TerraformDocuments.zipHash("ff")).isEqualTo("zh:ff");
    }

    @Test
    void signingKeysAreNormalised() {
        ObjectNode single = docs.normaliseSigningKeys(MAPPER.readTree(TerraformTestSupport.gpgKeyJson("ABCD1234"))).orElseThrow();
        assertThat(single.path("gpg_public_keys")).hasSize(1);
        JsonNode key = single.path("gpg_public_keys").get(0);
        assertThat(str(key, "key_id")).isEqualTo("ABCD1234");
        assertThat(str(key, "ascii_armor")).contains("BEGIN PGP PUBLIC KEY BLOCK");
        assertThat(str(key, "trust_signature")).isEmpty();
        assertThat(str(key, "source")).isEmpty();
        assertThat(str(key, "source_url")).isEmpty();

        ObjectNode list = docs.normaliseSigningKeys(MAPPER.readTree("{\"gpg_public_keys\":[" + TerraformTestSupport.gpgKeyJson(" K1 ")
                + "," + TerraformTestSupport.gpgKeyJson("K2").replace("}", ",\"source\":\"Acme\",\"source_url\":\"https://acme\"}") + "]}")).orElseThrow();
        assertThat(list.path("gpg_public_keys")).hasSize(2);
        assertThat(str(list.path("gpg_public_keys").get(0), "key_id")).isEqualTo("K1");
        assertThat(str(list.path("gpg_public_keys").get(1), "source")).isEqualTo("Acme");

        assertThat(docs.normaliseSigningKeys(null)).isEmpty();
        assertThat(docs.normaliseSigningKeys(MAPPER.readTree("[]"))).isEmpty();
        assertThat(docs.normaliseSigningKeys(MAPPER.readTree("{}"))).isEmpty();
        assertThat(docs.normaliseSigningKeys(MAPPER.readTree("{\"key_id\":\"A\",\"ascii_armor\":\"not a key\"}"))).isEmpty();
        assertThat(docs.normaliseSigningKeys(MAPPER.readTree("{\"key_id\":\"\",\"ascii_armor\":\"-----BEGIN PGP PUBLIC KEY BLOCK-----\"}"))).isEmpty();
        assertThat(docs.normaliseSigningKeys(MAPPER.readTree("{\"gpg_public_keys\":[]}"))).isEmpty();
        assertThat(docs.normaliseSigningKeys(MAPPER.readTree("{\"gpg_public_keys\":[{\"key_id\":\"A\"}]}"))).isEmpty();
    }

    @Test
    void protocolsSettingIsSplitAndDeduplicated() {
        assertThat(TerraformDocuments.parseProtocols("5.0,6.0")).containsExactly("5.0", "6.0");
        assertThat(TerraformDocuments.parseProtocols(" 5.0 , 6.0,5.0,,")).containsExactly("5.0", "6.0");
        assertThat(TerraformDocuments.parseProtocols("")).isEmpty();
    }

    @Test
    void fileNameOfUrl() {
        assertThat(TerraformLayout.fileNameOf("https://x/a/b/c.zip?x=1#f")).isEqualTo("c.zip");
        assertThat(TerraformLayout.fileNameOf("https://x/a/b/c%20d.zip")).isEqualTo("c d.zip");
        assertThat(TerraformLayout.fileNameOf("https://x/")).isEqualTo("x");
        assertThat(TerraformLayout.fileNameOf("")).isNull();
        assertThat(TerraformLayout.fileNameOf(null)).isNull();
        assertThat(TerraformLayout.providerFileUrl(BASE, "a", "b", "1.0.0", "c d.zip")).isEqualTo(BASE + "/files/a/b/1.0.0/c%20d.zip");
        assertThat(TerraformLayout.moduleFileUrl(BASE, "a", "b", "c", "1.0.0", "tgz")).isEqualTo(BASE + "/files/modules/a/b/c/1.0.0.tgz");
    }
}
