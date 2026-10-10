package dev.granary.format.docker;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Blob upload sessions of hosted repositories. A session is a pair of files under {@code <dataDir>/tmp/docker}:
 * {@code <uuid>.data} grows with every PATCH and {@code <uuid>.meta} remembers the repository the session belongs
 * to, so a session id cannot be replayed against another repository. Sessions that are neither completed nor
 * cancelled within {@link #TTL} are swept.
 */
@Component
public class DockerUploads {

    public static final Duration TTL = Duration.ofHours(24);
    static final String DATA_SUFFIX = ".data";
    static final String META_SUFFIX = ".meta";

    private static final Logger log = LoggerFactory.getLogger(DockerUploads.class);

    private final Path dir;
    private final StorageService storage;
    private final Clock clock;

    @Autowired
    public DockerUploads(GranaryProperties properties, StorageService storage) {
        this(properties.getDataDir().resolve("tmp").resolve("docker"), storage, Clock.systemUTC());
    }

    DockerUploads(Path dir, StorageService storage, Clock clock) {
        this.dir = dir;
        this.storage = storage;
        this.clock = clock;
    }

    /** An open upload session; {@link #size()} is the number of bytes received so far. */
    public record Session(String uuid, UUID repositoryId, String name, Instant created, Path data) {

        public long size() {
            try {
                return Files.exists(data) ? Files.size(data) : 0;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public Path directory() {
        return dir;
    }

    public Session start(Repository repo, String name) throws IOException {
        Files.createDirectories(dir);
        String uuid = UUID.randomUUID().toString();
        Instant created = clock.instant();
        Properties meta = new Properties();
        meta.setProperty("repository", repo.getId().toString());
        meta.setProperty("name", name);
        meta.setProperty("created", Long.toString(created.toEpochMilli()));
        try (OutputStream out = Files.newOutputStream(dir.resolve(uuid + META_SUFFIX))) {
            meta.store(out, null);
        }
        Files.write(dir.resolve(uuid + DATA_SUFFIX), new byte[0]);
        return new Session(uuid, repo.getId(), name, created, dir.resolve(uuid + DATA_SUFFIX));
    }

    /** The session {@code uuid} of {@code repo}, or empty when it never existed, belongs to another repository or expired. */
    public Optional<Session> find(Repository repo, String uuid) throws IOException {
        if (uuid == null || !DockerPath.isValidUploadId(uuid)) {
            return Optional.empty();
        }
        Path meta = dir.resolve(uuid + META_SUFFIX);
        Path data = dir.resolve(uuid + DATA_SUFFIX);
        if (!Files.isRegularFile(meta) || !Files.isRegularFile(data)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(meta)) {
            props.load(in);
        }
        if (!repo.getId().toString().equals(props.getProperty("repository"))) {
            return Optional.empty();
        }
        Instant created;
        try {
            created = Instant.ofEpochMilli(Long.parseLong(props.getProperty("created", "0")));
        } catch (NumberFormatException e) {
            created = Instant.EPOCH;
        }
        if (created.plus(TTL).isBefore(clock.instant())) {
            cancel(new Session(uuid, repo.getId(), props.getProperty("name", ""), created, data));
            return Optional.empty();
        }
        return Optional.of(new Session(uuid, repo.getId(), props.getProperty("name", ""), created, data));
    }

    /** Appends the bytes of {@code in} to the session and returns the new total size. */
    public long append(Session session, InputStream in) throws IOException {
        try (OutputStream out = Files.newOutputStream(session.data(), StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {
            in.transferTo(out);
        }
        return session.size();
    }

    /**
     * Verifies the received bytes against {@code expected}, stores them as the blob {@code blobs/sha256/<hex>} of the
     * repository and removes the session. A mismatch keeps the session (clients may retry) and fails with
     * {@code DIGEST_INVALID}.
     */
    public StoredContent complete(Repository repo, Session session, Digest expected) throws IOException {
        Digest actual;
        try (InputStream in = Files.newInputStream(session.data())) {
            actual = Digest.of(in);
        }
        if (!actual.equals(expected)) {
            throw DockerError.badRequest(OciErrorCode.DIGEST_INVALID,
                    "uploaded content has digest " + actual + ", not " + expected,
                    Map.of("expected", expected.toString(), "actual", actual.toString()));
        }
        String path = DockerLayout.blobPath(actual);
        StoredContent stored = ConcurrentWrites.retryOnce(path, () -> {
            try (InputStream in = Files.newInputStream(session.data())) {
                return storage.store(repo, path, in, MediaTypes.OCTET_STREAM, Asset.Origin.UPLOADED, false);
            }
        });
        cancel(session);
        return stored;
    }

    public void cancel(Session session) throws IOException {
        Files.deleteIfExists(session.data());
        Files.deleteIfExists(dir.resolve(session.uuid() + META_SUFFIX));
    }

    /** Removes sessions older than {@link #TTL}; runs hourly and may be called directly. Returns how many were removed. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public int sweep() {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int removed = 0;
        Instant cutoff = clock.instant().minus(TTL);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path meta : files.filter(p -> p.getFileName().toString().endsWith(META_SUFFIX)).toList()) {
                String uuid = meta.getFileName().toString();
                uuid = uuid.substring(0, uuid.length() - META_SUFFIX.length());
                Instant created = createdOf(meta);
                if (created.isBefore(cutoff)) {
                    Files.deleteIfExists(dir.resolve(uuid + DATA_SUFFIX));
                    Files.deleteIfExists(meta);
                    removed++;
                }
            }
            // data files whose meta file is gone are leftovers of a crash mid-start
            for (Path data : Files.list(dir).filter(p -> p.getFileName().toString().endsWith(DATA_SUFFIX)).toList()) {
                String uuid = data.getFileName().toString();
                uuid = uuid.substring(0, uuid.length() - DATA_SUFFIX.length());
                if (!Files.exists(dir.resolve(uuid + META_SUFFIX))
                        && Files.getLastModifiedTime(data).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(data);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("could not sweep docker upload sessions in {}: {}", dir, e.getMessage());
        }
        if (removed > 0) {
            log.info("removed {} expired docker upload session(s)", removed);
        }
        return removed;
    }

    private Instant createdOf(Path meta) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(meta)) {
            props.load(in);
            return Instant.ofEpochMilli(Long.parseLong(props.getProperty("created", "0")));
        } catch (IOException | NumberFormatException e) {
            return Instant.EPOCH;
        }
    }
}
