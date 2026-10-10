package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.GranaryException;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CargoSettingsTest {

    private static Repository proxyWith(Map<String, String> settings) {
        Repository repo = CargoTestSupport.proxy("cargo-proxy", "https://index.crates.io/");
        repo.setFormatSettings(settings);
        return repo;
    }

    @Test
    void descriptorsDeclareEveryKeyWithItsDefault() {
        assertThat(CargoSettings.descriptors()).extracting(SettingDescriptor::key)
                .containsExactly("cargo.authRequired", "cargo.downloadUrl", "cargo.apiUrl");
        SettingDescriptor auth = CargoSettings.descriptors().get(0);
        assertThat(auth.type()).isEqualTo(SettingDescriptor.Type.BOOLEAN);
        assertThat(auth.defaultValue()).isEqualTo("false");
        assertThat(auth.appliesTo()).containsExactlyInAnyOrder(RepositoryType.values());
        SettingDescriptor dl = CargoSettings.descriptors().get(1);
        assertThat(dl.type()).isEqualTo(SettingDescriptor.Type.STRING);
        assertThat(dl.defaultValue()).isEqualTo("https://static.crates.io/crates/{crate}/{crate}-{version}.crate");
        assertThat(dl.appliesTo()).containsExactly(RepositoryType.PROXY);
        SettingDescriptor api = CargoSettings.descriptors().get(2);
        // "none" must be accepted by the settings editor, which only takes http(s) values in URL fields
        assertThat(api.type()).isEqualTo(SettingDescriptor.Type.STRING);
        assertThat(api.description()).contains("\"none\"");
        assertThat(api.defaultValue()).isEqualTo("https://crates.io");
        assertThat(api.appliesTo()).containsExactly(RepositoryType.PROXY);
    }

    @Test
    void defaults() {
        Repository repo = proxyWith(Map.of());
        assertThat(CargoSettings.authRequired(repo)).isFalse();
        assertThat(CargoSettings.downloadUrl(repo)).isEqualTo(CargoSettings.DEFAULT_DOWNLOAD_URL);
        assertThat(CargoSettings.apiUrl(repo)).contains("https://crates.io");
    }

    @Test
    void explicitValues() {
        Repository repo = proxyWith(Map.of("cargo.authRequired", "true", "cargo.downloadUrl", " https://mirror/{crate} ",
                "cargo.apiUrl", "https://mirror.example.com/api/"));
        assertThat(CargoSettings.authRequired(repo)).isTrue();
        assertThat(CargoSettings.downloadUrl(repo)).isEqualTo("https://mirror/{crate}");
        assertThat(CargoSettings.apiUrl(repo)).contains("https://mirror.example.com/api");
        assertThat(CargoSettings.apiUrl(proxyWith(Map.of("cargo.apiUrl", "none")))).isEmpty();
    }

    @Test
    void validValuesPass() {
        assertThatCode(() -> CargoSettings.validate(proxyWith(Map.of()))).doesNotThrowAnyException();
        assertThatCode(() -> CargoSettings.validate(proxyWith(Map.of("cargo.authRequired", "yes",
                "cargo.downloadUrl", "https://mirror/crates", "cargo.apiUrl", "NONE")))).doesNotThrowAnyException();
        assertThatCode(() -> CargoSettings.validate(proxyWith(Map.of("cargo.authRequired", "", "cargo.apiUrl", " "))))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidValuesAreRefused() {
        assertThatThrownBy(() -> CargoSettings.validate(proxyWith(Map.of("cargo.authRequired", "maybe"))))
                .isInstanceOf(GranaryException.class)
                .satisfies(e -> assertThat(((GranaryException) e).code()).isEqualTo("repository.cargo.authRequired.invalid"));
        assertThatThrownBy(() -> CargoSettings.validate(proxyWith(Map.of("cargo.downloadUrl", "ftp://x/{crate}"))))
                .isInstanceOf(GranaryException.class)
                .satisfies(e -> assertThat(((GranaryException) e).code()).isEqualTo("repository.cargo.downloadUrl.invalid"));
        assertThatThrownBy(() -> CargoSettings.validate(proxyWith(Map.of("cargo.apiUrl", "crates.io"))))
                .isInstanceOf(GranaryException.class)
                .satisfies(e -> assertThat(((GranaryException) e).code()).isEqualTo("repository.cargo.apiUrl.invalid"));
    }
}
