package dev.granary.format.pypi;

import java.util.HexFormat;

/**
 * BLAKE2b (RFC 7693) with a configurable digest length and no key, which is what the upload API's
 * {@code blake2_256_digest} field is computed with ({@code hashlib.blake2b(digest_size=32)}). The JDK ships
 * no BLAKE2 provider, and verifying every digest a client claims is worth a hundred lines.
 */
public final class Blake2b {

    private static final long[] IV = {
            0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
            0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L};

    private static final byte[][] SIGMA = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
            {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
            {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
            {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
            {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
            {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
            {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
            {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
            {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0},
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3}};

    private static final int BLOCK = 128;

    private final int digestLength;
    private final long[] h = new long[8];
    private final long[] m = new long[16];
    private final long[] v = new long[16];
    private final byte[] buffer = new byte[BLOCK];
    private int buffered;
    private long counterLow;
    private long counterHigh;
    private boolean finished;

    /** @param digestLength output length in bytes, 1 to 64 */
    public Blake2b(int digestLength) {
        if (digestLength < 1 || digestLength > 64) {
            throw new IllegalArgumentException("BLAKE2b digest length must be 1..64 bytes, not " + digestLength);
        }
        this.digestLength = digestLength;
        System.arraycopy(IV, 0, h, 0, 8);
        h[0] ^= 0x01010000L ^ digestLength;
    }

    public void update(byte[] data, int offset, int length) {
        if (finished) {
            throw new IllegalStateException("digest already computed");
        }
        int pos = offset;
        int remaining = length;
        while (remaining > 0) {
            if (buffered == BLOCK) {
                // the buffer only ever holds a full block while more data follows, so this is never the last block
                increment(BLOCK);
                compress(buffer, 0, false);
                buffered = 0;
            }
            int n = Math.min(BLOCK - buffered, remaining);
            System.arraycopy(data, pos, buffer, buffered, n);
            buffered += n;
            pos += n;
            remaining -= n;
        }
    }

    public void update(byte[] data) {
        update(data, 0, data.length);
    }

    public byte[] digest() {
        if (finished) {
            throw new IllegalStateException("digest already computed");
        }
        finished = true;
        increment(buffered);
        for (int i = buffered; i < BLOCK; i++) {
            buffer[i] = 0;
        }
        compress(buffer, 0, true);
        byte[] out = new byte[digestLength];
        for (int i = 0; i < digestLength; i++) {
            out[i] = (byte) (h[i / 8] >>> (8 * (i % 8)));
        }
        return out;
    }

    private void increment(int bytes) {
        long before = counterLow;
        counterLow += bytes;
        if (Long.compareUnsigned(counterLow, before) < 0) {
            counterHigh++;
        }
    }

    private void compress(byte[] block, int offset, boolean last) {
        for (int i = 0; i < 16; i++) {
            m[i] = readLittleEndian(block, offset + 8 * i);
        }
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(IV, 0, v, 8, 8);
        v[12] ^= counterLow;
        v[13] ^= counterHigh;
        if (last) {
            v[14] = ~v[14];
        }
        for (int round = 0; round < 12; round++) {
            byte[] s = SIGMA[round];
            g(0, 4, 8, 12, m[s[0]], m[s[1]]);
            g(1, 5, 9, 13, m[s[2]], m[s[3]]);
            g(2, 6, 10, 14, m[s[4]], m[s[5]]);
            g(3, 7, 11, 15, m[s[6]], m[s[7]]);
            g(0, 5, 10, 15, m[s[8]], m[s[9]]);
            g(1, 6, 11, 12, m[s[10]], m[s[11]]);
            g(2, 7, 8, 13, m[s[12]], m[s[13]]);
            g(3, 4, 9, 14, m[s[14]], m[s[15]]);
        }
        for (int i = 0; i < 8; i++) {
            h[i] ^= v[i] ^ v[i + 8];
        }
    }

    private void g(int a, int b, int c, int d, long x, long y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = v[a] + v[b] + y;
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    private static long readLittleEndian(byte[] b, int off) {
        long r = 0;
        for (int i = 7; i >= 0; i--) {
            r = (r << 8) | (b[off + i] & 0xffL);
        }
        return r;
    }

    /** The hex digest of {@code data} with the given output length in bytes. */
    public static String hex(byte[] data, int digestLength) {
        Blake2b b = new Blake2b(digestLength);
        b.update(data);
        return HexFormat.of().formatHex(b.digest());
    }

    /** {@code hashlib.blake2b(data, digest_size=32).hexdigest()}, the value the upload API calls {@code blake2_256_digest}. */
    public static String hex256(byte[] data) {
        return hex(data, 32);
    }
}
