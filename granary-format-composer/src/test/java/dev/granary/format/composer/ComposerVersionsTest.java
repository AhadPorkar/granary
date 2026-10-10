package dev.granary.format.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The expected values in {@code normalize.json}, {@code branches.json} and {@code compare.json} were produced by
 * Composer 2.8 itself ({@code VersionParser::normalize}, {@code parseStability}, {@code normalizeBranch} and PHP's
 * {@code version_compare}), so these tests hold the port to the real thing.
 */
class ComposerVersionsTest {

    @Test
    void normalizesExactlyLikeComposer() {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (JsonNode row : ComposerTestSupport.resourceJson("normalize.json")) {
            String input = row.get(0).stringValue();
            JsonNode expected = row.get(1);
            String actual = ComposerVersions.tryNormalize(input).orElse(null);
            String wanted = expected.isNull() ? null : expected.stringValue();
            if (wanted == null ? actual != null : !wanted.equals(actual)) {
                failures.add("normalize(\"" + input + "\") = " + actual + ", Composer says " + wanted);
            }
            if (wanted != null) {
                String stability = ComposerVersions.parseStability(input);
                if (!row.get(2).stringValue().equals(stability)) {
                    failures.add("parseStability(\"" + input + "\") = " + stability + ", Composer says " + row.get(2).stringValue());
                }
            }
            checked++;
        }
        assertThat(checked).isGreaterThan(90);
        assertThat(failures).isEmpty();
    }

    @Test
    void normalizesBranchesLikeComposer() {
        List<String> failures = new ArrayList<>();
        for (JsonNode row : ComposerTestSupport.resourceJson("branches.json")) {
            String actual = ComposerVersions.normalizeBranch(row.get(0).stringValue());
            if (!row.get(1).stringValue().equals(actual)) {
                failures.add("normalizeBranch(\"" + row.get(0).stringValue() + "\") = " + actual + ", Composer says " + row.get(1).stringValue());
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void comparesLikePhpVersionCompare() {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (JsonNode row : ComposerTestSupport.resourceJson("compare.json")) {
            String a = row.get(0).stringValue();
            String b = row.get(1).stringValue();
            int expected = row.get(2).intValue();
            int actual = ComposerVersions.compare(a, b);
            if (actual != expected) {
                failures.add("compare(" + a + ", " + b + ") = " + actual + ", PHP says " + expected);
            }
            checked++;
        }
        assertThat(checked).isGreaterThan(1000);
        assertThat(failures).isEmpty();
    }

    @Test
    void normalizeRejectsWhatComposerRejects() {
        assertThatThrownBy(() -> ComposerVersions.normalize("not a version")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ComposerVersions.normalize(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ComposerVersions.tryNormalize("1.0.*")).isEmpty();
    }

    @Test
    void stripsStabilityFlags() {
        assertThat(ComposerVersions.normalize("1.0.0@beta")).isEqualTo("1.0.0.0");
        assertThat(ComposerVersions.normalize("dev-main@dev")).isEqualTo("dev-main");
    }

    @Test
    void devVersionsAreBranchesAndDevSuffixes() {
        assertThat(ComposerVersions.isDev("dev-main")).isTrue();
        assertThat(ComposerVersions.isDev("1.x-dev")).isTrue();
        assertThat(ComposerVersions.isDev("1.9999999.9999999.9999999-dev")).isTrue();
        assertThat(ComposerVersions.isDev("1.0.0.0-RC1-dev")).isTrue();
        assertThat(ComposerVersions.isDev("1.0.0")).isFalse();
        assertThat(ComposerVersions.isDev("1.0.0-beta2")).isFalse();
        assertThat(ComposerVersions.isDev("v2.0.0-RC1")).isFalse();
    }

    @Test
    void sortsNewestFirstWithBranchesLast() {
        List<String> versions = new ArrayList<>(List.of(
                "1.0.0.0", "dev-main", "2.0.0.0-beta1", "1.10.0.0", "2.0.0.0", "1.9.0.0", "dev-feature/x",
                "1.0.0.0-RC1", "2.0.0.0-dev", "1.9999999.9999999.9999999-dev"));
        versions.sort(ComposerVersions.NEWEST_FIRST);
        assertThat(versions).containsExactly(
                "2.0.0.0", "2.0.0.0-beta1", "2.0.0.0-dev", "1.9999999.9999999.9999999-dev", "1.10.0.0", "1.9.0.0",
                "1.0.0.0", "1.0.0.0-RC1", "dev-feature/x", "dev-main");
    }

    @Test
    void canonicalizesLikePhp() {
        assertThat(ComposerVersions.canonicalize("1.0.0-beta2")).isEqualTo("1.0.0.beta.2");
        assertThat(ComposerVersions.canonicalize("1.0rc1")).isEqualTo("1.0.rc.1");
        assertThat(ComposerVersions.canonicalize("1_0+1")).isEqualTo("1.0.1");
    }
}
