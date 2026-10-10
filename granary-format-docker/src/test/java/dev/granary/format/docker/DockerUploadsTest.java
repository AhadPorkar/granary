package dev.granary.format.docker;

import static dev.granary.format.docker.DockerTestSupport.hosted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import dev.granary.core.model.Repository;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;

class DockerUploadsTest {

    @TempDir
    Path tmp;

    private final FakeStorage storage = new FakeStorage();
    private final Repository repo = hosted("docker-hosted");
    private final Repository other = hosted("other");
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private DockerUploads uploads() {
        return new DockerUploads(tmp.resolve("tmp/docker"), storage.mock, clock);
    }

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void chunkedUploadEndsAsABlob() throws IOException {
        DockerUploads uploads = uploads();
        DockerUploads.Session session = uploads.start(repo, "acme/app");
        assertThat(session.uuid()).isNotBlank();
        assertThat(session.size()).isZero();
        assertThat(session.name()).isEqualTo("acme/app");
        assertThat(uploads.directory()).isEqualTo(tmp.resolve("tmp/docker"));

        assertThat(uploads.append(session, in("hello "))).isEqualTo(6);
        assertThat(uploads.append(session, in("world"))).isEqualTo(11);
        assertThat(uploads.find(repo, session.uuid())).isPresent();
        assertThat(uploads.find(repo, session.uuid()).orElseThrow().size()).isEqualTo(11);

        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        Digest digest = Digest.of(data);
        StoredContent stored = uploads.complete(repo, session, digest);
        assertThat(stored.asset().getPath()).isEqualTo(DockerLayout.blobPath(digest));
        assertThat(stored.asset().getContentType()).isEqualTo(MediaTypes.OCTET_STREAM);
        assertThat(stored.asset().isMetadata()).isFalse();
        assertThat(storage.bytes(repo, DockerLayout.blobPath(digest))).isEqualTo(data);
        assertThat(uploads.find(repo, session.uuid())).isEmpty();
        assertThat(Files.list(tmp.resolve("tmp/docker")).count()).isZero();
    }

    @Test
    void digestMismatchKeepsTheSession() throws IOException {
        DockerUploads uploads = uploads();
        DockerUploads.Session session = uploads.start(repo, "acme/app");
        uploads.append(session, in("data"));
        Digest wrong = Digest.of("other".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> uploads.complete(repo, session, wrong))
                .isInstanceOf(DockerError.class)
                .satisfies(e -> assertThat(((DockerError) e).code()).isEqualTo(OciErrorCode.DIGEST_INVALID));
        assertThat(uploads.find(repo, session.uuid())).isPresent();
        assertThat(storage.paths(repo)).isEmpty();
    }

    @Test
    void sessionsBelongToTheirRepository() throws IOException {
        DockerUploads uploads = uploads();
        DockerUploads.Session session = uploads.start(repo, "x");
        assertThat(uploads.find(other, session.uuid())).isEmpty();
        assertThat(uploads.find(repo, "no-such")).isEmpty();
        assertThat(uploads.find(repo, "../../etc/passwd")).isEmpty();
        assertThat(uploads.find(repo, null)).isEmpty();
    }

    @Test
    void cancelRemovesEverything() throws IOException {
        DockerUploads uploads = uploads();
        DockerUploads.Session session = uploads.start(repo, "x");
        uploads.append(session, in("abc"));
        uploads.cancel(session);
        assertThat(uploads.find(repo, session.uuid())).isEmpty();
        assertThat(Files.list(tmp.resolve("tmp/docker")).count()).isZero();
        uploads.cancel(session); // idempotent
    }

    @Test
    void expiredSessionsAreSwept() throws IOException {
        DockerUploads uploads = uploads();
        DockerUploads.Session old = uploads.start(repo, "x");
        uploads.append(old, in("abc"));
        now.set(now.get().plus(Duration.ofHours(12)));
        DockerUploads.Session fresh = uploads.start(repo, "y");
        Files.writeString(tmp.resolve("tmp/docker/orphan.data"), "leftover");
        Files.setLastModifiedTime(tmp.resolve("tmp/docker/orphan.data"),
                java.nio.file.attribute.FileTime.from(now.get().minus(Duration.ofDays(3))));
        now.set(now.get().plus(Duration.ofHours(13)));

        assertThat(uploads.sweep()).isEqualTo(2);
        assertThat(uploads.find(repo, old.uuid())).isEmpty();
        assertThat(uploads.find(repo, fresh.uuid())).isPresent();
        assertThat(Files.exists(tmp.resolve("tmp/docker/orphan.data"))).isFalse();
        assertThat(uploads.sweep()).isZero();
    }

    @Test
    void findRefusesExpiredSessions() throws IOException {
        DockerUploads uploads = uploads();
        DockerUploads.Session session = uploads.start(repo, "x");
        now.set(now.get().plus(DockerUploads.TTL).plusSeconds(1));
        assertThat(uploads.find(repo, session.uuid())).isEmpty();
        assertThat(Files.list(tmp.resolve("tmp/docker")).count()).isZero();
    }

    @Test
    void sweepOnMissingDirectoryIsHarmless() {
        assertThat(new DockerUploads(tmp.resolve("nowhere"), storage.mock, clock).sweep()).isZero();
    }

    @Test
    void aConcurrentUploadOfTheSameBlobIsNotAnError() throws IOException {
        // another push stored the same layer a moment earlier, so the unique key refuses the first insert
        AtomicInteger attempts = new AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            if (attempts.getAndIncrement() == 0) {
                throw new DataIntegrityViolationException("uk_asset_path");
            }
            return storage.putStream(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                    inv.getArgument(4), inv.getArgument(5), null);
        }).when(storage.mock).store(any(), anyString(), any(), any(), any(), anyBoolean());
        DockerUploads uploads = uploads();
        DockerUploads.Session session = uploads.start(repo, "acme/app");
        uploads.append(session, in("shared base layer"));
        Digest digest = Digest.of("shared base layer".getBytes(StandardCharsets.UTF_8));

        StoredContent stored = uploads.complete(repo, session, digest);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(stored.asset().getPath()).isEqualTo(DockerLayout.blobPath(digest));
        assertThat(storage.text(repo, DockerLayout.blobPath(digest))).isEqualTo("shared base layer");
        assertThat(uploads.find(repo, session.uuid())).isEmpty();
    }

    @Test
    void otherStorageFailuresAreNotRetried() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            attempts.incrementAndGet();
            throw new java.io.UncheckedIOException(new IOException("disk full"));
        }).when(storage.mock).store(any(), anyString(), any(), any(), any(), anyBoolean());
        DockerUploads uploads = uploads();
        DockerUploads.Session session = uploads.start(repo, "acme/app");
        uploads.append(session, in("data"));
        assertThatThrownBy(() -> uploads.complete(repo, session, Digest.of("data".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(java.io.UncheckedIOException.class);
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(uploads.find(repo, session.uuid())).isPresent();
    }
}
