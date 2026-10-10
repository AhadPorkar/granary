package dev.granary.format.cocoapods;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.cocoapods.CocoaPodsPath.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CocoaPodsPathTest {

    @Test
    void indexDocuments() {
        assertThat(CocoaPodsPath.parse("").kind()).isEqualTo(Kind.ROOT);
        assertThat(CocoaPodsPath.parse(null).kind()).isEqualTo(Kind.ROOT);
        assertThat(CocoaPodsPath.parse("CocoaPods-version.yml").kind()).isEqualTo(Kind.VERSION_FILE);
        assertThat(CocoaPodsPath.parse("all_pods.txt").kind()).isEqualTo(Kind.ALL_PODS);
        assertThat(CocoaPodsPath.parse("deprecated_podspecs.txt").kind()).isEqualTo(Kind.DEPRECATED);
        CocoaPodsPath shard = CocoaPodsPath.parse("all_pods_versions_a_7_5.txt");
        assertThat(shard.kind()).isEqualTo(Kind.SHARD);
        assertThat(shard.fragment()).containsExactly("a", "7", "5");
        assertThat(CocoaPodsPath.parse("all_pods_versions.txt").fragment()).isEmpty();
        assertThat(CocoaPodsPath.parse("all_pods_versions.txt").kind()).isEqualTo(Kind.SHARD);
        assertThat(CocoaPodsPath.parse("all_pods_versions_a7_5ff.txt").fragment()).containsExactly("a7", "5ff");
    }

    @ParameterizedTest
    @ValueSource(strings = {"all_pods_versions_A_7_5.txt", "all_pods_versions_g_7_5.txt", "all_pods_versions__a.txt",
            "all_pods_versions_a_7_5.json", "AlgoliaSearch.yml", "cocoapods-version.yml", "all_pods.txt.etag", "index.html"})
    void lookalikesAreUnknown(String path) {
        assertThat(CocoaPodsPath.parse(path).kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void podspecsInShardedAndShortForm() {
        CocoaPodsPath sharded = CocoaPodsPath.parse("Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json");
        assertThat(sharded.kind()).isEqualTo(Kind.PODSPEC);
        assertThat(sharded.fragment()).containsExactly("a", "7", "5");
        assertThat(sharded.name()).isEqualTo("AFNetworking");
        assertThat(sharded.version()).isEqualTo("4.0.1");

        CocoaPodsPath shortForm = CocoaPodsPath.parse("Specs/AFNetworking/4.0.1/AFNetworking.podspec.json");
        assertThat(shortForm.kind()).isEqualTo(Kind.PODSPEC);
        assertThat(shortForm.fragment()).isEmpty();
        assertThat(shortForm.name()).isEqualTo("AFNetworking");
    }

    @Test
    void podNamesThatLookLikeShardsOrContainOddCharacters() {
        CocoaPodsPath hexName = CocoaPodsPath.parse("Specs/1/2/3/abc/1.0/abc.podspec.json");
        assertThat(hexName.name()).isEqualTo("abc");
        assertThat(hexName.fragment()).containsExactly("1", "2", "3");
        assertThat(CocoaPodsPath.parse("Specs/e/5/f/ID.me WebVerify/1.0/ID.me WebVerify.podspec.json").name()).isEqualTo("ID.me WebVerify");
        assertThat(CocoaPodsPath.parse("Specs/d/6/c/Floater💩/0.1/Floater💩.podspec.json").name()).isEqualTo("Floater💩");
        assertThat(CocoaPodsPath.parse("Specs/a/a/8/!ProtoCompiler/3.21.5/!ProtoCompiler.podspec.json").name()).isEqualTo("!ProtoCompiler");
        assertThat(CocoaPodsPath.parse("Specs/3/3/6/-/0.0.1/-.podspec.json").name()).isEqualTo("-");
    }

    @Test
    void podspecFileMustBeNamedAfterThePod() {
        assertThat(CocoaPodsPath.parse("Specs/a/7/5/AFNetworking/4.0.1/Other.podspec.json").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(CocoaPodsPath.parse("Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(CocoaPodsPath.parse("Specs/A/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(CocoaPodsPath.parse("Specs/x/AFNetworking/4.0.1/AFNetworking.podspec.json").kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void versionDirectories() {
        CocoaPodsPath version = CocoaPodsPath.parse("Specs/MyPod/1.0.0");
        assertThat(version.kind()).isEqualTo(Kind.VERSION);
        assertThat(version.name()).isEqualTo("MyPod");
        assertThat(version.version()).isEqualTo("1.0.0");
        assertThat(CocoaPodsPath.parse("Specs/MyPod/1.0.0/").kind()).isEqualTo(Kind.VERSION);
        CocoaPodsPath sharded = CocoaPodsPath.parse("Specs/0/f/e/MyPod/2.0.0-beta.1");
        assertThat(sharded.kind()).isEqualTo(Kind.VERSION);
        assertThat(sharded.fragment()).containsExactly("0", "f", "e");
        // a pod directory is not a version
        assertThat(CocoaPodsPath.parse("Specs/a/7/5/AFNetworking").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(CocoaPodsPath.parse("Specs/MyPod").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(CocoaPodsPath.parse("Specs").kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void sourcesAndTheUploadEndpoint() {
        CocoaPodsPath source = CocoaPodsPath.parse("sources/AFNetworking/4.0.1/4.0.1.zip");
        assertThat(source.kind()).isEqualTo(Kind.SOURCE);
        assertThat(source.name()).isEqualTo("AFNetworking");
        assertThat(source.version()).isEqualTo("4.0.1");
        assertThat(source.filename()).isEqualTo("4.0.1.zip");
        assertThat(CocoaPodsPath.parse("sources/AFNetworking/4.0.1").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(CocoaPodsPath.parse("sources/AFNetworking/4.0.1/x/y.zip").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(CocoaPodsPath.parse("api/pods").kind()).isEqualTo(Kind.API_PODS);
        assertThat(CocoaPodsPath.parse("api/pods/").kind()).isEqualTo(Kind.API_PODS);
        assertThat(CocoaPodsPath.parse("api/v1/pods").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(CocoaPodsPath.sourcePath("A", "1.0", "a.zip")).isEqualTo("sources/A/1.0/a.zip");
        assertThat(CocoaPodsPath.sourceDirectory("A", "1.0")).isEqualTo("sources/A/1.0/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/all_pods.txt", "Specs//MyPod/1.0", "Specs/../all_pods.txt", "Specs/./MyPod/1.0",
            "sources/MyPod/1.0/..", "sources/My\\Pod/1.0/a.zip", "Specs/My\u0000Pod/1.0", "Specs/MyPod/1.0\n", "//", "..",
            "Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json/..", "sources/x/1.0/a\u007fb.zip"})
    void anythingThatCouldEscapeOrConfuseTheStoreIsInvalid(String path) {
        assertThat(CocoaPodsPath.parse(path).kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void onlyIndexDocumentsAreMetadata() {
        assertThat(CocoaPodsPath.parse("CocoaPods-version.yml").isMetadata()).isTrue();
        assertThat(CocoaPodsPath.parse("all_pods_versions_a_7_5.txt").isMetadata()).isTrue();
        assertThat(CocoaPodsPath.parse("all_pods.txt").isMetadata()).isTrue();
        assertThat(CocoaPodsPath.parse("deprecated_podspecs.txt").isMetadata()).isTrue();
        assertThat(CocoaPodsPath.parse("Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json").isMetadata()).isFalse();
        assertThat(CocoaPodsPath.parse("sources/AFNetworking/4.0.1/4.0.1.zip").isMetadata()).isFalse();
        assertThat(CocoaPodsPath.parse("whatever").isMetadata()).isFalse();
    }
}
