package dev.granary.core.blob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBlobStoreTest {

    private static final String HELLO_SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @TempDir
    Path dir;

    FileBlobStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new FileBlobStore("default", dir);
    }

    @Test
    void storesContentAddressedBySha256() throws IOException {
        BlobRef ref = store.store(bytes("hello"));

        assertThat(ref.sha256()).isEqualTo(HELLO_SHA256);
        assertThat(ref.size()).isEqualTo(5);
        assertThat(store.exists(HELLO_SHA256)).isTrue();
        assertThat(store.pathFor(HELLO_SHA256)).isEqualTo(dir.resolve("content/2c/f2/" + HELLO_SHA256));
        try (InputStream in = store.open(HELLO_SHA256).orElseThrow()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
    }

    @Test
    void storingTheSameBytesTwiceIsIdempotent() throws IOException {
        BlobRef first = store.store(bytes("hello"));
        BlobRef second = store.store(bytes("hello"));

        assertThat(second).isEqualTo(first);
        assertThat(store.stats().blobCount()).isEqualTo(1);
    }

    @Test
    void openOfUnknownDigestIsEmpty() throws IOException {
        assertThat(store.open("0".repeat(64))).isEmpty();
        assertThat(store.exists("0".repeat(64))).isFalse();
    }

    @Test
    void rejectsMalformedDigests() {
        assertThatThrownBy(() -> store.open("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open("ZZ".repeat(32))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open("abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteRemovesBlobAndReportsWhetherItExisted() throws IOException {
        store.store(bytes("hello"));

        assertThat(store.delete(HELLO_SHA256)).isTrue();
        assertThat(store.delete(HELLO_SHA256)).isFalse();
        assertThat(store.exists(HELLO_SHA256)).isFalse();
    }

    @Test
    void leavesNoTemporaryFilesBehind() throws IOException {
        store.store(bytes("one"));
        store.store(bytes("two"));

        try (var files = Files.list(dir.resolve("tmp"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void statsCountBlobsAndBytes() throws IOException {
        store.store(bytes("hello"));
        store.store(bytes("granary"));

        BlobStore.BlobStoreStats stats = store.stats();
        assertThat(stats.blobCount()).isEqualTo(2);
        assertThat(stats.totalBytes()).isEqualTo(5 + 7);
        assertThat(stats.availableBytes()).isPositive();
    }

    @Test
    void concurrentWritersOfTheSameContentAllSucceed() throws Exception {
        byte[] payload = new byte[256 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<BlobRef>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(() -> store.store(new ByteArrayInputStream(payload)));
            }
            List<Future<BlobRef>> results = pool.invokeAll(tasks);
            BlobRef expected = results.get(0).get();
            for (Future<BlobRef> f : results) {
                assertThat(f.get()).isEqualTo(expected);
            }
            assertThat(store.stats().blobCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void blobRefValidatesItsArguments() {
        assertThatThrownBy(() -> new BlobRef("short", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BlobRef(HELLO_SHA256, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
