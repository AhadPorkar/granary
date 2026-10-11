package dev.granary.core.blob;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A blob just stored by {@link BlobStore#storePinned} that compaction leaves alone until it is closed. The writer
 * closes it once the reference to the blob is committed, or abandoned: until then nothing in the database says the
 * blob is used, and its modification time alone would protect it only for the grace period.
 */
public final class PinnedBlob implements AutoCloseable {

    private final BlobRef ref;
    private final Runnable release;
    private final AtomicBoolean released = new AtomicBoolean();

    public PinnedBlob(BlobRef ref, Runnable release) {
        this.ref = Objects.requireNonNull(ref, "ref");
        this.release = Objects.requireNonNull(release, "release");
    }

    public BlobRef ref() {
        return ref;
    }

    /** Lets compaction consider the blob again. Only the first call has an effect. */
    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            release.run();
        }
    }
}
