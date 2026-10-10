package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.storage.Checksums;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishRequestTest {

    @TempDir
    Path temp;

    private static byte[] body(String json, byte[] crate) {
        return PublishRequest.encode(json.getBytes(StandardCharsets.UTF_8), crate);
    }

    @Test
    void readsMetadataAndBuffersTheCrate() throws IOException {
        byte[] crate = CargoTestSupport.crate("acme", "1.0.0");
        byte[] body = CargoTestSupport.publishBody(CargoTestSupport.metadata("acme", "1.0.0"), crate);
        Path file;
        try (PublishRequest request = PublishRequest.read(new ByteArrayInputStream(body), temp.resolve("tmp"))) {
            assertThat(request.metadata().get("name").stringValue()).isEqualTo("acme");
            assertThat(request.crateSize()).isEqualTo(crate.length);
            assertThat(request.sha256()).isEqualTo(Checksums.hex("SHA-256", crate));
            try (var in = request.openCrate()) {
                assertThat(in.readAllBytes()).isEqualTo(crate);
            }
            file = request.crateFile();
            assertThat(file).exists().startsWith(temp.resolve("tmp"));
        }
        assertThat(file).doesNotExist();
    }

    @Test
    void lengthsAreLittleEndian() {
        byte[] body = PublishRequest.encode(new byte[300], new byte[70000]);
        assertThat(Arrays.copyOfRange(body, 0, 4)).containsExactly(0x2c, 0x01, 0, 0);
        assertThat(Arrays.copyOfRange(body, 304, 308)).containsExactly(0x70, 0x11, 0x01, 0);
    }

    @Test
    void emptyBody() {
        assertRefused(new byte[0], "the publish request is empty");
    }

    @Test
    void truncatedMetadataLength() {
        assertRefused(new byte[]{1, 0}, "missing the metadata length");
    }

    @Test
    void truncatedMetadata() {
        byte[] body = body("{\"name\":\"x\"}", new byte[]{1});
        assertRefused(Arrays.copyOf(body, 8), "expected 12 bytes of metadata, got 4");
    }

    @Test
    void metadataMustBeAnObject() {
        assertRefused(body("[1,2,3]", new byte[]{1}), "not a JSON object");
        assertRefused(body("{not json", new byte[]{1}), "not a JSON object");
        assertRefused(body("", new byte[]{1}), "not a JSON object");
    }

    @Test
    void oversizedMetadataIsRefusedBeforeReading() {
        byte[] body = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        assertRefused(body, "larger than the limit");
    }

    @Test
    void missingCrateLength() {
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[4 + json.length + 2];
        body[0] = (byte) json.length;
        System.arraycopy(json, 0, body, 4, json.length);
        assertRefused(body, "missing the crate length");
    }

    @Test
    void truncatedCrate() {
        byte[] body = body("{}", new byte[100]);
        assertRefused(Arrays.copyOf(body, body.length - 10), "expected 100 bytes of crate, got 90");
    }

    @Test
    void trailingBytesAreRefused() {
        byte[] body = body("{}", new byte[10]);
        byte[] longer = Arrays.copyOf(body, body.length + 1);
        assertRefused(longer, "unexpected data after the crate");
    }

    @Test
    void emptyCrate() {
        assertRefused(body("{}", new byte[0]), "the crate file is empty");
    }

    @Test
    void failedReadsLeaveNoScratchFile() throws IOException {
        Path dir = temp.resolve("scratch");
        assertRefused(dir, Arrays.copyOf(body("{}", new byte[100]), 50), "truncated");
        assertRefused(dir, body("{}", new byte[0]), "empty");
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).isEmpty();
        }
    }

    private void assertRefused(byte[] body, String message) {
        assertRefused(temp.resolve("tmp"), body, message);
    }

    private static void assertRefused(Path dir, byte[] body, String message) {
        assertThatThrownBy(() -> PublishRequest.read(new ByteArrayInputStream(body), dir))
                .isInstanceOf(CargoError.class)
                .hasMessageContaining(message)
                .satisfies(e -> assertThat(((CargoError) e).status().value()).isEqualTo(400));
    }
}
