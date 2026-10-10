package dev.granary.format.hex;

import dev.granary.core.config.GranaryProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The RSA key Granary signs its own registry documents with (hosted repositories and merged group
 * documents). One key per server, created on first use and kept under {@code <dataDir>/hex/};
 * clients trust it through {@code mix hex.repo add … --public-key} or {@code repo_public_key} in
 * {@code rebar.config}. Signatures are RSA PKCS#1 v1.5 over SHA-512, what {@code hex_core} verifies.
 */
@Component
public class HexSigningKey {

    static final String PRIVATE_KEY_FILE = "registry_signing_key.pem";
    static final String PUBLIC_KEY_FILE = "public_key.pem";
    private static final Logger log = LoggerFactory.getLogger(HexSigningKey.class);

    private final Path directory;
    private volatile KeyPair pair;
    private volatile String publicPem;

    @Autowired
    public HexSigningKey(GranaryProperties properties) {
        this(properties.getDataDir().resolve("hex"));
    }

    public HexSigningKey(Path directory) {
        this.directory = directory;
    }

    public byte[] sign(byte[] payload) {
        try {
            Signature signature = Signature.getInstance("SHA512withRSA");
            signature.initSign(keyPair().getPrivate());
            signature.update(payload);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not sign registry document", e);
        }
    }

    public boolean verify(byte[] payload, byte[] signature) {
        return verify(payload, signature, keyPair().getPublic());
    }

    public static boolean verify(byte[] payload, byte[] signature, PublicKey publicKey) {
        try {
            Signature verifier = Signature.getInstance("SHA512withRSA");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** The PEM ({@code BEGIN PUBLIC KEY}, SubjectPublicKeyInfo) served at {@code /public_key}. */
    public String publicKeyPem() {
        keyPair();
        return publicPem;
    }

    public PublicKey publicKey() {
        return keyPair().getPublic();
    }

    /** The fingerprint {@code mix hex.repo add --fetch-public-key} and {@code mix hex.repo list} show. */
    public String fingerprint() {
        return fingerprint(publicKey());
    }

    /**
     * {@code SHA256:<base64>} of the key in SSH wire form, exactly what {@code ssh:hostkey_fingerprint(sha256, Key)}
     * (and so {@code hex_repo:fingerprint/1}) computes for an RSA public key.
     */
    public static String fingerprint(PublicKey key) {
        if (!(key instanceof java.security.interfaces.RSAPublicKey rsa)) {
            throw new IllegalArgumentException("not an RSA public key");
        }
        java.io.ByteArrayOutputStream blob = new java.io.ByteArrayOutputStream();
        sshString(blob, "ssh-rsa".getBytes(StandardCharsets.US_ASCII));
        sshString(blob, rsa.getPublicExponent().toByteArray());
        sshString(blob, rsa.getModulus().toByteArray());
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob.toByteArray());
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sshString(java.io.ByteArrayOutputStream out, byte[] value) {
        out.write(value.length >>> 24);
        out.write(value.length >>> 16);
        out.write(value.length >>> 8);
        out.write(value.length);
        out.writeBytes(value);
    }

    private KeyPair keyPair() {
        KeyPair current = pair;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (pair == null) {
                pair = loadOrGenerate();
                publicPem = toPem(pair.getPublic());
            }
            return pair;
        }
    }

    private KeyPair loadOrGenerate() {
        Path privateFile = directory.resolve(PRIVATE_KEY_FILE);
        try {
            if (Files.exists(privateFile)) {
                try {
                    return load(privateFile);
                } catch (GeneralSecurityException | IllegalArgumentException e) {
                    throw new IllegalStateException("the Hex signing key " + privateFile + " is not a readable RSA private "
                            + "key; restore it from a backup (deleting it creates a new key that clients must trust again)", e);
                }
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair created = generator.generateKeyPair();
            Files.createDirectories(directory);
            writePrivate(privateFile, created.getPrivate());
            Files.writeString(directory.resolve(PUBLIC_KEY_FILE), toPem(created.getPublic()), StandardCharsets.US_ASCII);
            log.info("created the Hex registry signing key in {}", directory);
            return created;
        } catch (IOException e) {
            throw new UncheckedIOException("could not read or write the Hex signing key in " + directory, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA is not available", e);
        }
    }

    private static KeyPair load(Path privateFile) throws IOException, GeneralSecurityException {
        byte[] der = pemBody(Files.readString(privateFile, StandardCharsets.US_ASCII));
        KeyFactory factory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(der));
        if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
            throw new GeneralSecurityException("the stored Hex signing key is not an RSA CRT key");
        }
        PublicKey publicKey = factory.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        return new KeyPair(publicKey, privateKey);
    }

    /**
     * Writes the private key so that it is never readable by others, not even for a moment (the file is created
     * owner-only before the key goes in), and never half-written (it appears under its final name in one atomic
     * rename): a torn key file would stop every hosted repository from signing.
     */
    private static void writePrivate(Path file, PrivateKey key) throws IOException {
        String pem = pem("PRIVATE KEY", key.getEncoded());
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        Files.deleteIfExists(partial);
        try {
            Files.createFile(partial, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(partial); // not a POSIX file system: the data directory's own protection applies
        }
        Files.writeString(partial, pem, StandardCharsets.US_ASCII);
        try {
            Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String toPem(PublicKey key) {
        return pem("PUBLIC KEY", key.getEncoded());
    }

    public static PublicKey parsePublicKey(String pem) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pemBody(pem)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not an RSA public key in PEM form", e);
        }
    }

    private static String pem(String label, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }

    private static byte[] pemBody(String pem) {
        StringBuilder sb = new StringBuilder();
        for (String line : pem.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("-----")) {
                continue;
            }
            sb.append(trimmed);
        }
        return Base64.getDecoder().decode(sb.toString());
    }
}
