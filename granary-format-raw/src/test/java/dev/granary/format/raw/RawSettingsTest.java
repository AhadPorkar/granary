package dev.granary.format.raw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.GranaryException;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RawSettingsTest {

    private final Repository repo = RawTestSupport.hosted("raw-hosted", "raw");

    @Test
    void descriptorsNameTheThreeKeysWithTheProfileDefault() {
        List<SettingDescriptor> descriptors = RawSettings.descriptors("**/PACKAGES*,web/**");
        assertThat(descriptors).extracting(SettingDescriptor::key)
                .containsExactly("raw.metadataPatterns", "raw.contentDisposition", "raw.listingEnabled");
        assertThat(descriptors.get(0).defaultValue()).isEqualTo("**/PACKAGES*,web/**");
        assertThat(descriptors.get(0).type()).isEqualTo(SettingDescriptor.Type.STRING);
        assertThat(descriptors.get(1).defaultValue()).isEqualTo("inline");
        assertThat(descriptors.get(2).type()).isEqualTo(SettingDescriptor.Type.BOOLEAN);
        assertThat(descriptors.get(2).defaultValue()).isEqualTo("true");
    }

    @Test
    void repositoryPatternsOverrideTheDefaults() {
        GlobMatcher defaults = GlobMatcher.of("**/index.html");
        assertThat(RawSettings.metadataPatterns(repo, defaults)).isSameAs(defaults);
        repo.setFormatSettings(Map.of("raw.metadataPatterns", "**/*.json"));
        GlobMatcher own = RawSettings.metadataPatterns(repo, defaults);
        assertThat(own.matches("a/b.json")).isTrue();
        assertThat(own.matches("index.html")).isFalse();
        assertThat(RawSettings.metadataPatterns(repo, defaults)).isSameAs(own);
        repo.setFormatSettings(Map.of("raw.metadataPatterns", "   "));
        assertThat(RawSettings.metadataPatterns(repo, defaults)).isSameAs(defaults);
    }

    @Test
    void dispositionAndListingDefaults() {
        assertThat(RawSettings.attachment(repo)).isFalse();
        assertThat(RawSettings.listingEnabled(repo)).isTrue();
        repo.setFormatSettings(Map.of("raw.contentDisposition", "Attachment", "raw.listingEnabled", "no"));
        assertThat(RawSettings.attachment(repo)).isTrue();
        assertThat(RawSettings.listingEnabled(repo)).isFalse();
    }

    @Test
    void validationRefusesBadValues() {
        repo.setFormatSettings(Map.of("raw.contentDisposition", "download"));
        assertThatThrownBy(() -> RawSettings.validate(repo)).isInstanceOf(GranaryException.class)
                .satisfies(e -> assertThat(((GranaryException) e).code()).isEqualTo("repository.raw.contentDisposition"));
        repo.setFormatSettings(Map.of("raw.listingEnabled", "maybe"));
        assertThatThrownBy(() -> RawSettings.validate(repo))
                .satisfies(e -> assertThat(((GranaryException) e).code()).isEqualTo("repository.raw.listingEnabled"));
        repo.setFormatSettings(Map.of("raw.metadataPatterns", "a\\b"));
        assertThatThrownBy(() -> RawSettings.validate(repo))
                .satisfies(e -> assertThat(((GranaryException) e).code()).isEqualTo("repository.raw.metadataPatterns"));
        repo.setFormatSettings(Map.of("raw.metadataPatterns", "**/x, y/*", "raw.contentDisposition", "INLINE", "raw.listingEnabled", "1"));
        RawSettings.validate(repo);
    }
}
