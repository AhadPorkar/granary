package dev.granary.format.hex;

import static dev.granary.format.hex.HexTestSupport.golden;
import static dev.granary.format.hex.HexTestSupport.goldenKey;
import static dev.granary.format.hex.HexTestSupport.goldenText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPairGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HexSigningKeyTest {

    @TempDir
    Path tempDir;

    @Test
    void fingerprintMatchesWhatMixShowsForHexpm() {
        String hexpm = goldenText("hexpm_public_key.pem");
        String fingerprint = HexSigningKey.fingerprint(HexSigningKey.parsePublicKey(hexpm));
        assertThat(fingerprint).isEqualTo("SHA256:O1LOYhHFW4kcrblKAxROaDEzLD8bn1seWbe5tq8TRsk");
        assertThat(fingerprint).isEqualTo(goldenText("hexpm_fingerprint.txt").strip());
    }

    @Test
    void loadsAnExistingKeyAndAgreesWithHexCore() {
        HexSigningKey key = goldenKey(tempDir);
        // hex_core's pem_encode ends with a blank line; the key itself is the same
        assertThat(key.publicKeyPem()).isEqualTo(goldenText("public_key.pem").strip() + "\n");
        assertThat(key.fingerprint()).isEqualTo(goldenText("fingerprint.txt").strip());
        assertThat(HexSigningKey.toPem(key.publicKey())).isEqualTo(key.publicKeyPem());
    }

    @Test
    void signaturesVerifyAndTamperingIsDetected() {
        HexSigningKey key = goldenKey(tempDir);
        byte[] payload = "registry payload".getBytes(StandardCharsets.UTF_8);
        byte[] signature = key.sign(payload);
        assertThat(signature).hasSize(256);
        assertThat(key.verify(payload, signature)).isTrue();
        assertThat(HexSigningKey.verify(payload, signature, HexSigningKey.parsePublicKey(goldenText("public_key.pem")))).isTrue();

        byte[] tampered = payload.clone();
        tampered[0] ^= 1;
        assertThat(key.verify(tampered, signature)).isFalse();
        assertThat(key.verify(payload, new byte[0])).isFalse();
        assertThat(HexSigningKey.verify(payload, signature, HexSigningKey.parsePublicKey(goldenText("hexpm_public_key.pem"))))
                .isFalse();
    }

    @Test
    void generatesAKeyOnFirstUseAndKeepsIt() throws Exception {
        Path dir = tempDir.resolve("data").resolve("hex");
        HexSigningKey first = new HexSigningKey(dir);
        String pem = first.publicKeyPem();
        assertThat(pem).startsWith("-----BEGIN PUBLIC KEY-----\n").endsWith("-----END PUBLIC KEY-----\n");
        assertThat(dir.resolve(HexSigningKey.PRIVATE_KEY_FILE)).exists();
        assertThat(Files.readString(dir.resolve(HexSigningKey.PUBLIC_KEY_FILE))).isEqualTo(pem);
        assertThat(Files.readString(dir.resolve(HexSigningKey.PRIVATE_KEY_FILE))).startsWith("-----BEGIN PRIVATE KEY-----");
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(dir.resolve(HexSigningKey.PRIVATE_KEY_FILE)))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }

        HexSigningKey second = new HexSigningKey(dir);
        assertThat(second.publicKeyPem()).isEqualTo(pem);
        assertThat(second.fingerprint()).isEqualTo(first.fingerprint()).startsWith("SHA256:");
        byte[] payload = {1, 2, 3};
        assertThat(second.verify(payload, first.sign(payload))).isTrue();
    }

    @Test
    void refusesWhatIsNotAnRsaPublicKey() throws Exception {
        assertThatThrownBy(() -> HexSigningKey.parsePublicKey("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HexSigningKey.parsePublicKey("not a key at all!"))
                .isInstanceOf(IllegalArgumentException.class);
        var ec = KeyPairGenerator.getInstance("EC").generateKeyPair().getPublic();
        assertThatThrownBy(() -> HexSigningKey.fingerprint(ec)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pemParsingToleratesCarriageReturnsAndBlankLines() {
        String pem = goldenText("public_key.pem").replace("\n", "\r\n") + "\r\n\r\n";
        assertThat(HexSigningKey.fingerprint(HexSigningKey.parsePublicKey(pem)))
                .isEqualTo(goldenText("fingerprint.txt").strip());
        assertThat(golden("signing_key.pem")).isNotEmpty();
    }

    @Test
    void theKeyFileAppearsWholeAndOwnerOnly() throws Exception {
        Path dir = tempDir.resolve("atomic");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(HexSigningKey.PRIVATE_KEY_FILE + ".partial"), "left over by a crash");
        HexSigningKey key = new HexSigningKey(dir);
        key.publicKeyPem();
        assertThat(dir.resolve(HexSigningKey.PRIVATE_KEY_FILE + ".partial")).doesNotExist();
        assertThat(new HexSigningKey(dir).fingerprint()).isEqualTo(key.fingerprint());
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(dir.resolve(HexSigningKey.PRIVATE_KEY_FILE)))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    void aDamagedKeyFileIsReportedAsSuchAndNeverReplaced() throws Exception {
        Path dir = tempDir.resolve("damaged");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(HexSigningKey.PRIVATE_KEY_FILE), "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n");
        assertThatThrownBy(() -> new HexSigningKey(dir).publicKeyPem()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a readable RSA private key");
        assertThat(Files.readString(dir.resolve(HexSigningKey.PRIVATE_KEY_FILE))).contains("AAAA");
    }

    @Test
    void anUnreadableKeyDirectoryFailsLoudly() throws Exception {
        Path file = tempDir.resolve("not-a-directory");
        Files.writeString(file, "x");
        HexSigningKey key = new HexSigningKey(file.resolve("hex"));
        assertThatThrownBy(key::publicKeyPem).isInstanceOf(RuntimeException.class);
    }
}
