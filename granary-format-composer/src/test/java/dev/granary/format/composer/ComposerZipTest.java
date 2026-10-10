package dev.granary.format.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class ComposerZipTest {

    @TempDir
    Path dir;

    @Test
    void readsComposerJsonAtTheRoot() throws IOException {
        Path zip = write(ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", "1.0.0", null)));
        assertThat(ComposerZip.readComposerJson(zip, ComposerTestSupport.JSON).get("name").stringValue()).isEqualTo("acme/lib");
    }

    @Test
    void readsComposerJsonOneDirectoryDown() throws IOException {
        Path zip = write(ComposerTestSupport.packageZip(ComposerTestSupport.composerJson("acme/lib", null, null)));
        assertThat(ComposerZip.readComposerJson(zip, ComposerTestSupport.JSON).get("name").stringValue()).isEqualTo("acme/lib");
    }

    @Test
    void ignoresMacOsMetadataDirectories() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("__MACOSX/lib/._composer.json", new byte[] {0});
        entries.put("lib/composer.json", "{\"name\":\"acme/lib\"}".getBytes(StandardCharsets.UTF_8));
        Path zip = write(ComposerTestSupport.zip(entries));
        assertThat(ComposerZip.readComposerJson(zip, ComposerTestSupport.JSON).get("name").stringValue()).isEqualTo("acme/lib");
    }

    @Test
    void rejectsArchivesWithSeveralTopLevelDirectories() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a/composer.json", "{\"name\":\"acme/a\"}".getBytes(StandardCharsets.UTF_8));
        entries.put("b/composer.json", "{\"name\":\"acme/b\"}".getBytes(StandardCharsets.UTF_8));
        assertInvalid(write(ComposerTestSupport.zip(entries)), "composer.json");
    }

    @Test
    void rejectsArchivesWithoutComposerJson() throws IOException {
        assertInvalid(write(ComposerTestSupport.zip(Map.of("src/Lib.php", new byte[0]))), "composer.json");
        assertInvalid(write(ComposerTestSupport.zip(Map.of("a/b/composer.json", "{}".getBytes(StandardCharsets.UTF_8)))), "composer.json");
    }

    @Test
    void rejectsBrokenComposerJson() throws IOException {
        assertInvalid(write(ComposerTestSupport.rootZip("{\"name\": ")), "composer.json");
        assertInvalid(write(ComposerTestSupport.rootZip("[1, 2]")), "composer.json");
    }

    @Test
    void rejectsWhatIsNotAZip() throws IOException {
        assertInvalid(write("this is not a zip archive".getBytes(StandardCharsets.UTF_8)), "zip");
    }

    @Test
    void rejectsOversizedComposerJson() throws IOException {
        String huge = "{\"name\":\"acme/lib\",\"description\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}";
        assertInvalid(write(ComposerTestSupport.rootZip(huge)), "composer.json");
    }

    private void assertInvalid(Path zip, String messagePart) {
        assertThatThrownBy(() -> ComposerZip.readComposerJson(zip, ComposerTestSupport.JSON))
                .isInstanceOfSatisfying(ComposerError.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).containsIgnoringCase(messagePart);
                });
    }

    private Path write(byte[] bytes) throws IOException {
        Path file = Files.createTempFile(dir, "pkg", ".zip");
        Files.write(file, bytes);
        return file;
    }
}
