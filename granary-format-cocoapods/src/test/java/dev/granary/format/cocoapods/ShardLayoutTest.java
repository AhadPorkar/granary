package dev.granary.format.cocoapods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShardLayoutTest {

    @Test
    void theStandardLayoutMatchesTheCdn() {
        // AFNetworking is listed in https://cdn.cocoapods.org/all_pods_versions_a_7_5.txt
        ShardLayout layout = ShardLayout.STANDARD;
        assertThat(layout.fragment("AFNetworking")).containsExactly("a", "7", "5");
        assertThat(layout.shardFileName("AFNetworking")).isEqualTo("all_pods_versions_a_7_5.txt");
        assertThat(layout.podDirectory("AFNetworking")).isEqualTo("Specs/a/7/5/AFNetworking");
        assertThat(layout.podspecPath("AFNetworking", "4.0.1")).isEqualTo("Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json");
        assertThat(layout.totalLength()).isEqualTo(3);
    }

    @Test
    void unicodeAndEmojiNamesShardByTheirUtf8Digest() {
        assertThat(ShardLayout.STANDARD.podspecPath("Floater💩", "1.0")).isEqualTo("Specs/d/6/c/Floater💩/1.0/Floater💩.podspec.json");
        assertThat(ShardLayout.STANDARD.podDirectory("ID.me WebVerify")).isEqualTo("Specs/e/5/f/ID.me WebVerify");
    }

    @Test
    void otherLayoutsSplitTheDigestDifferently() {
        ShardLayout flat = new ShardLayout(List.of());
        assertThat(flat.fragment("AFNetworking")).isEmpty();
        assertThat(flat.shardFileName("AFNetworking")).isEqualTo("all_pods_versions.txt");
        assertThat(flat.podspecPath("AFNetworking", "1.0")).isEqualTo("Specs/AFNetworking/1.0/AFNetworking.podspec.json");

        ShardLayout two = new ShardLayout(List.of(2));
        assertThat(two.fragment("AFNetworking")).containsExactly("a7");
        assertThat(two.shardFileName("AFNetworking")).isEqualTo("all_pods_versions_a7.txt");

        ShardLayout mixed = new ShardLayout(List.of(1, 2, 3));
        assertThat(mixed.fragment("AFNetworking")).containsExactly("a", "75", "d45");
        assertThat(mixed.podDirectory("AFNetworking")).isEqualTo("Specs/a/75/d45/AFNetworking");
        assertThat(mixed.split("a75d45ff")).containsExactly("a", "75", "d45");
    }

    @Test
    void fragmentsAreRecognisedOnlyInTheirOwnShape() {
        assertThat(ShardLayout.STANDARD.isFragment(List.of("a", "7", "5"))).isTrue();
        assertThat(ShardLayout.STANDARD.isFragment(List.of("a", "7"))).isFalse();
        assertThat(ShardLayout.STANDARD.isFragment(List.of("a7", "5", "0"))).isFalse();
        assertThat(ShardLayout.STANDARD.isFragment(List.of())).isFalse();
        assertThat(new ShardLayout(List.of()).isFragment(List.of())).isTrue();
    }

    @Test
    void shardFileNamesAndDirectories() {
        assertThat(ShardLayout.shardFileName(List.of())).isEqualTo("all_pods_versions.txt");
        assertThat(ShardLayout.shardFileName(List.of("0", "f", "e"))).isEqualTo("all_pods_versions_0_f_e.txt");
        assertThat(ShardLayout.shardDirectory(List.of("0", "f", "e"))).isEqualTo("Specs/0/f/e/");
        assertThat(ShardLayout.shardDirectory(List.of())).isEqualTo("Specs/");
    }

    @Test
    void impossibleLayoutsAreRejected() {
        assertThatThrownBy(() -> new ShardLayout(List.of(0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShardLayout(List.of(-1, 2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShardLayout(List.of(16, 17))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ShardLayout(List.of(16, 16)).totalLength()).isEqualTo(32);
        assertThatThrownBy(() -> ShardLayout.STANDARD.split("ab")).isInstanceOf(IllegalArgumentException.class);
    }
}
