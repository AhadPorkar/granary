package dev.granary.format.cocoapods;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PodIndexTest {

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void parsesCdnShardLines() {
        String shard = "AFNetworking/0.10.0/0.10.1/4.0.1\nAppseeAnalytics/2.4.7/2.4.8\nFuse/0.1.0\n";
        Map<String, List<String>> pods = PodIndex.parseShard(shard);
        assertThat(pods).containsOnlyKeys("AFNetworking", "AppseeAnalytics", "Fuse");
        assertThat(pods.get("AFNetworking")).containsExactly("0.10.0", "0.10.1", "4.0.1");
        assertThat(pods.get("Fuse")).containsExactly("0.1.0");
    }

    @Test
    void toleratesCrlfBlankLinesDuplicatesAndPodsWithoutVersions() {
        Map<String, List<String>> pods = PodIndex.parseShard("A/1.0/1.0//2.0/\r\n\r\n   \nB\n/1.0\nA/3.0\n");
        assertThat(pods.get("A")).containsExactly("1.0", "2.0", "3.0");
        assertThat(pods.get("B")).isEmpty();
        assertThat(pods).hasSize(2);
        assertThat(PodIndex.parseShard("")).isEmpty();
        assertThat(PodIndex.parseShard(null)).isEmpty();
    }

    @Test
    void rendersNamesByCodePointAndVersionsInCocoaPodsOrder() {
        Map<String, List<String>> pods = new LinkedHashMap<>();
        pods.put("b", List.of("1.0"));
        pods.put("AFNetworking", List.of("4.0.1", "0.10.0", "1.0RC1", "1.0", "0.9.0", "2.0.0-RC1", "2.0.0"));
        pods.put("Empty", List.of());
        pods.put("Floater💩", List.of("0.1"));
        assertThat(text(PodIndex.renderShard(pods)))
                .isEqualTo("AFNetworking/0.9.0/0.10.0/1.0RC1/1.0/2.0.0-RC1/2.0.0/4.0.1\nFloater💩/0.1\nb/1.0\n");
        assertThat(PodIndex.renderShard(Map.of())).isEmpty();
    }

    @Test
    void renderingIsDeterministicWhateverTheInputOrder() {
        Map<String, List<String>> one = new LinkedHashMap<>();
        one.put("X", List.of("2.0", "1.0"));
        one.put("A", List.of("1.0"));
        Map<String, List<String>> two = new LinkedHashMap<>();
        two.put("A", List.of("1.0"));
        two.put("X", List.of("1.0", "2.0", "1.0"));
        assertThat(PodIndex.renderShard(one)).isEqualTo(PodIndex.renderShard(two));
    }

    @Test
    void mergeIsTheUnionOfVersions() {
        Map<String, List<String>> hosted = Map.of("Mine", List.of("1.0.0"), "AFNetworking", List.of("4.0.2-internal"));
        Map<String, List<String>> trunk = new LinkedHashMap<>();
        trunk.put("AFNetworking", List.of("4.0.0", "4.0.1"));
        trunk.put("Other", List.of("0.1"));
        Map<String, List<String>> merged = PodIndex.mergeShards(List.of(hosted, trunk));
        assertThat(merged).containsOnlyKeys("Mine", "AFNetworking", "Other");
        assertThat(merged.get("AFNetworking")).containsExactlyInAnyOrder("4.0.2-internal", "4.0.0", "4.0.1");
        assertThat(text(PodIndex.renderShard(merged))).isEqualTo("AFNetworking/4.0.0/4.0.1/4.0.2-internal\nMine/1.0.0\nOther/0.1\n");
        assertThat(PodIndex.mergeShards(List.of())).isEmpty();
        assertThat(PodIndex.mergeShards(List.of(Map.of("A", List.of("1.0")), Map.of("A", List.of("1.0")))).get("A")).containsExactly("1.0");
    }

    @Test
    void filtersAShardOfACoarserLayoutDownToTheGroupsShard() {
        Map<String, List<String>> all = new LinkedHashMap<>();
        all.put("AFNetworking", List.of("4.0.1")); // a75d…
        all.put("Alamofire", List.of("5.0.0"));    // da20…
        assertThat(PodIndex.filterByHashPrefix(all, "a75")).containsOnlyKeys("AFNetworking");
        assertThat(PodIndex.filterByHashPrefix(all, "d")).containsOnlyKeys("Alamofire");
        assertThat(PodIndex.filterByHashPrefix(all, "")).isSameAs(all);
        assertThat(PodIndex.filterByHashPrefix(all, "fff")).isEmpty();
    }

    @Test
    void linesAreTrimmedSortedAndDeduplicated() {
        assertThat(PodIndex.lines("b\r\n a \n\n\tc\n")).containsExactly("b", "a", "c");
        assertThat(PodIndex.lines(null)).isEmpty();
        assertThat(text(PodIndex.renderLines(List.of("b", "a", "b", " c ", "", "🔒", "※ikemen")))).isEqualTo("a\nb\nc\n※ikemen\n🔒\n");
        assertThat(PodIndex.renderLines(List.of())).isEmpty();
    }

    @Test
    void translatesPodspecPathsBetweenLayouts() {
        assertThat(PodIndex.translatePodspecPath("Specs/AFNetworking/4.0.1/AFNetworking.podspec.json", ShardLayout.STANDARD))
                .contains("Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json");
        assertThat(PodIndex.translatePodspecPath("Specs/a7/AFNetworking/4.0.1/AFNetworking.podspec.json", ShardLayout.STANDARD))
                .contains("Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json");
        assertThat(PodIndex.translatePodspecPath("Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json", new ShardLayout(List.of())))
                .contains("Specs/AFNetworking/4.0.1/AFNetworking.podspec.json");
        assertThat(PodIndex.translatePodspecPath("not a path", ShardLayout.STANDARD)).isEmpty();
        assertThat(PodIndex.translatePodspecPath("Specs/../x/1.0/x.podspec.json", ShardLayout.STANDARD)).isEmpty();
    }
}
