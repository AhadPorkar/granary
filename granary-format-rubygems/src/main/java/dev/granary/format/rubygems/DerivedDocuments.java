package dev.granary.format.rubygems;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Index documents computed from other content (a hosted repository's records, a group's members) and kept in the
 * repository's storage as generated metadata. Each one remembers a fingerprint of its inputs; as long as the inputs
 * are unchanged it is served as stored, otherwise it is rebuilt once (concurrent requests wait for that build).
 *
 * <p>Building writes to a temporary file under {@code <dataDir>/tmp/rubygems/}, never to memory, because the
 * merged indexes of a group in front of rubygems.org are tens of megabytes.
 */
@Component
public class DerivedDocuments {

    /** Bumped whenever the way documents are built changes, so stored copies are rebuilt after an upgrade. */
    static final String GENERATION = "rubygems-1";

    private static final String MARKER = "derived:";

    /** Writes a document. */
    @FunctionalInterface
    public interface Writer {
        void write(OutputStream out) throws IOException;
    }

    private final StorageService storage;
    private final Path tempDir;
    /**
     * One lock per document (not striped): building a group's {@code versions} reads hosted members' {@code info}
     * documents, and two unrelated documents sharing a stripe could otherwise deadlock.
     */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Autowired
    public DerivedDocuments(StorageService storage, GranaryProperties properties) {
        this(storage, properties.getDataDir().resolve("tmp").resolve(RubygemsFormatHandler.FORMAT));
    }

    DerivedDocuments(StorageService storage, Path tempDir) {
        this.storage = storage;
        this.tempDir = tempDir;
    }

    /** The document at {@code path}, rebuilt with {@code writer} unless the stored one has {@code fingerprint}. */
    public StoredContent get(Repository repo, String path, String fingerprint, String contentType, Writer writer)
            throws IOException {
        String marker = MARKER + fingerprint;
        Optional<StoredContent> current = storage.find(repo, path);
        if (current.isPresent() && marker.equals(current.get().asset().getUpstreamEtag())) {
            return current.get();
        }
        ReentrantLock lock = locks.computeIfAbsent(repo.getId() + "/" + path, k -> new ReentrantLock());
        lock.lock();
        try {
            current = storage.find(repo, path);
            if (current.isPresent() && marker.equals(current.get().asset().getUpstreamEtag())) {
                return current.get();
            }
            Files.createDirectories(tempDir);
            Path temp = Files.createTempFile(tempDir, "derived-", ".tmp");
            try {
                MessageDigest md5 = GemPackage.digest("MD5");
                try (OutputStream out = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(temp), 64 * 1024), md5)) {
                    writer.write(out);
                }
                StoredContent stored;
                try (InputStream in = Files.newInputStream(temp)) {
                    stored = storage.store(repo, path, in, contentType, Asset.Origin.GENERATED, true);
                }
                Asset asset = storage.touchVerified(stored.asset(), marker, null);
                GemResponses.rememberMd5(asset.getBlobSha256(), HexFormat.of().formatHex(md5.digest()));
                return new StoredContent(asset, stored.blobStore());
            } finally {
                Files.deleteIfExists(temp);
            }
        } finally {
            lock.unlock();
        }
    }

    /** A fingerprint over the given parts (plus {@link #GENERATION}). */
    public static String fingerprint(Iterable<String> parts) {
        MessageDigest sha = GemPackage.digest("SHA-256");
        sha.update(GENERATION.getBytes(StandardCharsets.UTF_8));
        for (String part : parts) {
            sha.update((byte) '\n');
            sha.update(part.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    Path tempDir() {
        return tempDir;
    }
}
