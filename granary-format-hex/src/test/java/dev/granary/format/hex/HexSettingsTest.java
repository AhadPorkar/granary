package dev.granary.format.hex;

import static dev.granary.format.hex.HexTestSupport.group;
import static dev.granary.format.hex.HexTestSupport.hosted;
import static dev.granary.format.hex.HexTestSupport.withSetting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.GranaryException;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.RepositoryType;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class HexSettingsTest {

    @Test
    void descriptors() {
        List<SettingDescriptor> descriptors = HexSettings.descriptors();
        assertThat(descriptors).extracting(SettingDescriptor::key)
                .containsExactly(HexSettings.REPOSITORY_NAME, HexSettings.REVERT_WINDOW, HexSettings.VERIFY_UPSTREAM);
        assertThat(descriptors.get(0).appliesTo()).containsExactlyInAnyOrder(RepositoryType.HOSTED, RepositoryType.GROUP);
        assertThat(descriptors.get(1).appliesTo()).containsExactly(RepositoryType.HOSTED);
        assertThat(descriptors.get(1).defaultValue()).isEqualTo("60");
        assertThat(descriptors.get(2).appliesTo()).containsExactly(RepositoryType.GROUP);
        assertThat(descriptors.get(2).defaultValue()).isEqualTo("true");
    }

    @Test
    void repositoryNameDefaultsToTheGranaryName() {
        assertThat(HexSettings.repositoryName(hosted("hex-hosted"))).isEqualTo("hex-hosted");
        assertThat(HexSettings.repositoryName(withSetting(hosted("hex-hosted"), HexSettings.REPOSITORY_NAME, "  acme  ")))
                .isEqualTo("acme");
        assertThat(HexSettings.repositoryName(withSetting(hosted("hex-hosted"), HexSettings.REPOSITORY_NAME, "  ")))
                .isEqualTo("hex-hosted");
    }

    @Test
    void revertWindow() {
        assertThat(HexSettings.revertWindow(hosted("h"))).contains(Duration.ofMinutes(60));
        assertThat(HexSettings.revertWindow(withSetting(hosted("h"), HexSettings.REVERT_WINDOW, "5"))).contains(Duration.ofMinutes(5));
        assertThat(HexSettings.revertWindow(withSetting(hosted("h"), HexSettings.REVERT_WINDOW, "0"))).isEmpty();
        assertThat(HexSettings.revertWindow(withSetting(hosted("h"), HexSettings.REVERT_WINDOW, "-1"))).contains(Duration.ofMinutes(60));
        assertThat(HexSettings.revertWindow(withSetting(hosted("h"), HexSettings.REVERT_WINDOW, "lots"))).contains(Duration.ofMinutes(60));
    }

    @Test
    void verifyUpstream() {
        assertThat(HexSettings.verifyUpstream(group("g"))).isTrue();
        assertThat(HexSettings.verifyUpstream(withSetting(group("g"), HexSettings.VERIFY_UPSTREAM, "false"))).isFalse();
    }

    @Test
    void validation() {
        HexSettings.validate(hosted("plain"));
        HexSettings.validate(withSetting(withSetting(withSetting(hosted("h"), HexSettings.REPOSITORY_NAME, "acme.internal-1"),
                HexSettings.REVERT_WINDOW, "0"), HexSettings.VERIFY_UPSTREAM, "FALSE"));
        for (String bad : new String[] {"-acme", "acme:org", "acme/x", "ä", "a".repeat(101)}) {
            assertThatThrownBy(() -> HexSettings.validate(withSetting(hosted("h"), HexSettings.REPOSITORY_NAME, bad))).as(bad)
                    .isInstanceOf(GranaryException.class).hasMessageContaining(HexSettings.REPOSITORY_NAME);
        }
        for (String bad : new String[] {"-1", "1.5", "soon"}) {
            assertThatThrownBy(() -> HexSettings.validate(withSetting(hosted("h"), HexSettings.REVERT_WINDOW, bad))).as(bad)
                    .isInstanceOf(GranaryException.class).hasMessageContaining(HexSettings.REVERT_WINDOW);
        }
        assertThatThrownBy(() -> HexSettings.validate(withSetting(group("g"), HexSettings.VERIFY_UPSTREAM, "maybe")))
                .isInstanceOf(GranaryException.class).hasMessageContaining(HexSettings.VERIFY_UPSTREAM);
    }
}
