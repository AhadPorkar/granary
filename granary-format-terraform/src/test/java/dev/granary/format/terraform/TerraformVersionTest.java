package dev.granary.format.terraform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TerraformVersionTest {

    @ParameterizedTest
    @ValueSource(strings = {"0.0.1", "1.2.3", "10.20.30", "1.0.0-beta.2", "1.0.0-rc1", "1.0.0+build.7", "1.0.0-alpha+001", "v3.2.4"})
    void parsesSemanticVersions(String text) {
        assertThat(TerraformVersion.tryParse(text)).isPresent();
        assertThat(TerraformVersion.isValid(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1", "1.2", "1.2.3.4", "01.2.3", "1.2.x", "latest", "1.2.3-", "1.2.3+", "1.2.3-beta..1",
            "../1.2.3", "1.2.3/4", "v", "1.2.3 x", "99999999999999999999.0.0"})
    void rejectsEverythingElse(String text) {
        assertThat(TerraformVersion.tryParse(text)).isEmpty();
    }

    @Test
    void nullAndBlankAreInvalid() {
        assertThat(TerraformVersion.tryParse(null)).isEmpty();
        assertThat(TerraformVersion.isValid(null)).isFalse();
    }

    @Test
    void canonicalFormDropsTheLeadingVAndKeepsTheRest() {
        assertThat(TerraformVersion.tryParse("v1.2.3").orElseThrow().toString()).isEqualTo("1.2.3");
        assertThat(TerraformVersion.tryParse("1.2.3-beta.1+exp").orElseThrow().toString()).isEqualTo("1.2.3-beta.1+exp");
        TerraformVersion v = TerraformVersion.tryParse("4.5.6-rc.1").orElseThrow();
        assertThat(v.major()).isEqualTo(4);
        assertThat(v.minor()).isEqualTo(5);
        assertThat(v.patch()).isEqualTo(6);
        assertThat(v.isPrerelease()).isTrue();
        assertThat(v.prerelease()).contains("rc.1");
        assertThat(TerraformVersion.tryParse("4.5.6").orElseThrow().isPrerelease()).isFalse();
    }

    @Test
    void ordersLikeSemver() {
        List<String> expected = List.of("0.9.0", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
                "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0", "10.0.0");
        List<String> shuffled = new ArrayList<>(expected);
        java.util.Collections.reverse(shuffled);
        shuffled.sort(TerraformVersion.STRING_ORDER);
        assertThat(shuffled).containsExactlyElementsOf(expected);
    }

    @Test
    void buildMetadataIsIgnoredForEqualityAndOrder() {
        TerraformVersion a = TerraformVersion.tryParse("1.0.0+1").orElseThrow();
        TerraformVersion b = TerraformVersion.tryParse("1.0.0+2").orElseThrow();
        assertThat(a).isEqualByComparingTo(b).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void stringOrderPutsUnparsableVersionsLast() {
        List<String> list = new ArrayList<>(List.of("zzz", "1.0.0", "abc", "0.1.0"));
        list.sort(TerraformVersion.STRING_ORDER);
        assertThat(list).containsExactly("0.1.0", "1.0.0", "abc", "zzz");
        assertThat(TerraformVersion.STRING_ORDER.compare("v1.0.0", "1.0.0")).isNotEqualTo(0);
    }
}
