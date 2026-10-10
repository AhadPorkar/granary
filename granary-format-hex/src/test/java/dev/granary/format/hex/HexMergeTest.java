package dev.granary.format.hex;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.hex.HexRegistry.Dependency;
import dev.granary.format.hex.HexRegistry.Names;
import dev.granary.format.hex.HexRegistry.NamesEntry;
import dev.granary.format.hex.HexRegistry.Package;
import dev.granary.format.hex.HexRegistry.Release;
import dev.granary.format.hex.HexRegistry.Retirement;
import dev.granary.format.hex.HexRegistry.RetirementReason;
import dev.granary.format.hex.HexRegistry.Timestamp;
import dev.granary.format.hex.HexRegistry.Versions;
import dev.granary.format.hex.HexRegistry.VersionsEntry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class HexMergeTest {

    @Test
    void namesAreTheUnionWithTheLatestUpdate() {
        Names hosted = new Names("hex-hosted", List.of(
                new NamesEntry("acme", new Timestamp(200, 0)),
                new NamesEntry("shared", new Timestamp(100, 5))));
        Names upstream = new Names("hexpm", List.of(
                new NamesEntry("jason", null),
                new NamesEntry("shared", new Timestamp(100, 9)),
                new NamesEntry("", new Timestamp(1, 0))));
        Names merged = HexMerge.names("hex-group", List.of(hosted, upstream));
        assertThat(merged.repository()).isEqualTo("hex-group");
        assertThat(merged.packages()).containsExactly(
                new NamesEntry("acme", new Timestamp(200, 0)),
                new NamesEntry("jason", null),
                new NamesEntry("shared", new Timestamp(100, 9)));
    }

    @Test
    void versionsAreUnitedAndTheFirstMemberDecidesRetirement() {
        Versions hosted = new Versions("hex-hosted", List.of(
                new VersionsEntry("shared", List.of("1.0.0", "2.0.0"), List.of(0), List.of())));
        Versions upstream = new Versions("hexpm", List.of(
                new VersionsEntry("shared", List.of("0.9.0", "1.0.0", "1.5.0-rc.1"), List.of(1, 2), List.of(0)),
                new VersionsEntry("jason", List.of("1.4.4", "1.4.0"), List.of(), List.of(1))));
        Versions merged = HexMerge.versions("hex-group", List.of(hosted, upstream));
        assertThat(merged.repository()).isEqualTo("hex-group");
        assertThat(merged.packages()).containsExactly(
                new VersionsEntry("jason", List.of("1.4.0", "1.4.4"), List.of(), List.of(0)),
                new VersionsEntry("shared", List.of("0.9.0", "1.0.0", "1.5.0-rc.1", "2.0.0"), List.of(1, 2), List.of(0)));
    }

    @Test
    void packagesTakeEachReleaseFromTheFirstMemberThatHasIt() {
        byte[] inner = new byte[32];
        Release hosted100 = new Release("1.0.0", inner, List.of(new Dependency("a", "~> 1.0", false, "", "")), null, new byte[] {1});
        Release upstream100 = new Release("1.0.0", inner, List.of(), new Retirement(RetirementReason.SECURITY, "x"), new byte[] {2});
        Release upstream090 = new Release("0.9.0", inner, List.of(), null, new byte[] {3});
        Package merged = HexMerge.pkg("shared", "hex-group", List.of(
                new Package("shared", "hex-hosted", List.of(hosted100)),
                new Package("shared", "hexpm", List.of(upstream100, upstream090))));
        assertThat(merged.name()).isEqualTo("shared");
        assertThat(merged.repository()).isEqualTo("hex-group");
        assertThat(merged.releases()).extracting(Release::version).containsExactly("0.9.0", "1.0.0");
        assertThat(merged.release("1.0.0").orElseThrow().outerChecksum()).containsExactly(1);
        assertThat(merged.release("1.0.0").orElseThrow().isRetired()).isFalse();
    }

    @Test
    void advisoriesArePooledAndReindexed() {
        byte[] a = "advisory A".getBytes(StandardCharsets.UTF_8);
        byte[] b = "advisory B".getBytes(StandardCharsets.UTF_8);
        byte[] c = "advisory C".getBytes(StandardCharsets.UTF_8);
        Release first = new Release("1.0.0", new byte[0], List.of(), null, new byte[0], List.of(1), null);
        Release second = new Release("2.0.0", new byte[0], List.of(), null, new byte[0], List.of(0, 1, 7), null);
        Package merged = HexMerge.pkg("p", "g", List.of(
                new Package("p", "one", List.of(first), List.of(c, b)),
                new Package("p", "two", List.of(second), List.of(a, b))));
        assertThat(merged.advisories()).containsExactly(b, a);
        assertThat(merged.release("1.0.0").orElseThrow().advisoryIndexes()).containsExactly(0);
        assertThat(merged.release("2.0.0").orElseThrow().advisoryIndexes()).containsExactly(1, 0); // 7 dangled: dropped
    }

    @Test
    void nothingToMergeGivesEmptyDocuments() {
        assertThat(HexMerge.names("g", List.of()).packages()).isEmpty();
        assertThat(HexMerge.versions("g", List.of()).packages()).isEmpty();
        assertThat(HexMerge.pkg("p", "g", List.of()).releases()).isEmpty();
    }
}
