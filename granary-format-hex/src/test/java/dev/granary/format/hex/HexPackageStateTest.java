package dev.granary.format.hex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HexPackageStateTest {

    private static HexPackageState.Release release(String version) {
        return new HexPackageState.Release(version, "aa".repeat(32), "bb".repeat(32), "2024-01-01T00:00:00Z",
                "2024-01-01T00:00:00Z", "alice",
                List.of(new HexPackageState.Dependency("jason", "jason", "~> 1.4", false, "hexpm")),
                null, new HexPackageState.Meta("acme", "Ünïcode ✓", List.of("MIT"), Map.of("Source", "https://s.test"),
                List.of("mix"), "~> 1.15"), false, 1234);
    }

    @Test
    void releasesAreKeptInVersionOrder() {
        HexPackageState state = new HexPackageState("acme", null, null,
                List.of(release("1.10.0"), release("1.2.0"), release("1.2.0-rc.1")));
        assertThat(state.versions()).containsExactly("1.2.0-rc.1", "1.2.0", "1.10.0");
        assertThat(state.latest().orElseThrow().version()).isEqualTo("1.10.0");
        assertThat(new HexPackageState("x", null, null, null).releases()).isEmpty();
    }

    @Test
    void withAndWithoutReplaceAndRemoveReleases() {
        Instant now = Instant.parse("2025-02-03T04:05:06Z");
        HexPackageState state = new HexPackageState("acme", null, null, List.of())
                .with(release("1.0.0"), now)
                .with(release("0.1.0"), now);
        assertThat(state.insertedAt()).isEqualTo(now.toString());
        assertThat(state.updatedAt()).isEqualTo(now.toString());
        assertThat(state.versions()).containsExactly("0.1.0", "1.0.0");

        Instant later = now.plusSeconds(60);
        HexPackageState.Retirement retirement = new HexPackageState.Retirement("security", "use 1.0.1");
        HexPackageState retired = state.with(state.release("1.0.0").orElseThrow().withRetirement(retirement, later), later);
        assertThat(retired.versions()).containsExactly("0.1.0", "1.0.0");
        assertThat(retired.insertedAt()).isEqualTo(now.toString());
        assertThat(retired.updatedAt()).isEqualTo(later.toString());
        assertThat(retired.release("1.0.0").orElseThrow().retirement()).isEqualTo(retirement);
        assertThat(retired.release("1.0.0").orElseThrow().updatedAt()).isEqualTo(later.toString());

        HexPackageState removed = retired.without("1.0.0", later);
        assertThat(removed.versions()).containsExactly("0.1.0");
        assertThat(removed.release("1.0.0")).isEmpty();
        assertThat(removed.without("0.1.0", later).latest()).isEmpty();
    }

    @Test
    void latestFallsBackToPreReleases() {
        HexPackageState state = new HexPackageState("acme", null, null, List.of(release("2.0.0-rc.1"), release("1.0.0")));
        assertThat(state.latest().orElseThrow().version()).isEqualTo("1.0.0");
        HexPackageState pre = new HexPackageState("acme", null, null, List.of(release("2.0.0-rc.1")));
        assertThat(pre.latest().orElseThrow().version()).isEqualTo("2.0.0-rc.1");
    }

    @Test
    void jsonRoundTrip() {
        HexPackageState state = new HexPackageState("acme", "2024-01-01T00:00:00Z", "2024-01-02T00:00:00Z",
                List.of(release("1.0.0").withDocs(true, Instant.parse("2024-01-03T00:00:00Z"))));
        byte[] json = state.toJson();
        HexPackageState back = HexPackageState.fromJson(json);
        assertThat(back).isEqualTo(state);
        assertThat(back.releases().getFirst().hasDocs()).isTrue();
        assertThat(back.releases().getFirst().meta().description()).isEqualTo("Ünïcode ✓");
        assertThat(back.releases().getFirst().innerChecksumBytes()).hasSize(32);
        assertThat(new String(json, StandardCharsets.UTF_8)).contains("\"innerChecksum\"");
    }

    @Test
    void jsonFromANewerVersionIsStillReadable() {
        String json = """
                {"name":"acme","insertedAt":"2024-01-01T00:00:00Z","future":true,
                 "releases":[{"version":"1.0.0","innerChecksum":"zz","outerChecksum":null,"futureField":1,
                   "retirement":{"reason":"renamed","message":"now acme2","extra":1}}]}
                """;
        HexPackageState state = HexPackageState.fromJson(json.getBytes(StandardCharsets.UTF_8));
        HexPackageState.Release r = state.release("1.0.0").orElseThrow();
        assertThat(r.dependencies()).isEmpty();
        assertThat(r.meta()).isEqualTo(HexPackageState.Meta.EMPTY);
        assertThat(r.innerChecksumBytes()).isEmpty(); // not hex: treated as unknown rather than failing
        assertThat(r.outerChecksumBytes()).isEmpty();
        assertThat(r.inserted()).isEqualTo(Instant.EPOCH);
        assertThat(r.retirement().reasonCode()).isEqualTo(HexRegistry.RetirementReason.RENAMED);
        assertThat(new HexPackageState.Retirement("nonsense", null).reasonCode()).isEqualTo(HexRegistry.RetirementReason.OTHER);
    }

    @Test
    void unreadableJsonIsRefused() {
        assertThatThrownBy(() -> HexPackageState.fromJson("not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unreadable package state");
        assertThatThrownBy(() -> HexPackageState.fromJson("{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no name");
        assertThatThrownBy(() -> HexPackageState.fromJson("null".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void helpers() {
        assertThat(HexPackageState.path("acme")).isEqualTo(".hex/packages/acme.json");
        assertThat(HexPackageState.parseInstant(null)).isEqualTo(Instant.EPOCH);
        assertThat(HexPackageState.parseInstant("garbage")).isEqualTo(Instant.EPOCH);
        assertThat(HexPackageState.parseInstant("2024-01-01T00:00:00Z")).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        HexMetadata meta = HexMetadata.parse(HexTestSupport.metadata("acme", "1.0.0", "Desc"));
        HexPackageState.Meta m = HexPackageState.Meta.of(meta);
        assertThat(m.app()).isEqualTo("acme");
        assertThat(m.description()).isEqualTo("Desc");
        assertThat(m.licenses()).containsExactly("Apache-2.0");
        assertThat(m.links()).containsEntry("Source", "https://example.test/acme");
        assertThat(m.buildTools()).containsExactly("mix");
        assertThat(m.elixir()).isEqualTo("~> 1.15");
    }
}
