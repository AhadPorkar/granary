package dev.granary.format.pypi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Reference values computed with Python's {@code hashlib.blake2b(data, digest_size=32)} and the 64-byte default. */
class Blake2bTest {

    private static byte[] range(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    @Test
    void emptyInput() {
        assertThat(Blake2b.hex256(new byte[0])).isEqualTo("0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8");
        assertThat(Blake2b.hex(new byte[0], 64)).startsWith("786a02f742015903c6c6fd852552d272");
    }

    @Test
    void abc() {
        byte[] abc = "abc".getBytes(StandardCharsets.US_ASCII);
        assertThat(Blake2b.hex256(abc)).isEqualTo("bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319");
        assertThat(Blake2b.hex(abc, 64)).startsWith("ba80a53f981c4d0d6a2797b69f12f6e9");
    }

    @Test
    void exactlyOneBlock() {
        assertThat(Blake2b.hex256(range(128))).isEqualTo("c3582f71ebb2be66fa5dd750f80baae97554f3b015663c8be377cfcb2488c1d1");
        assertThat(Blake2b.hex(range(128), 64)).startsWith("2319e3789c47e2daa5fe807f61bec2a1");
    }

    @Test
    void oneBlockAndOneByte() {
        assertThat(Blake2b.hex256(range(129))).isEqualTo("f7f3c46ba2564ff4c4c162da1f5b605f9f1c4aa6a20652a9f9a337c1a2f5b9c9");
        assertThat(Blake2b.hex(range(129), 64)).startsWith("f59711d44a031d5f97a9413c065d1e61");
    }

    @Test
    void manyBlocksAndIncrementalUpdatesAgree() {
        byte[] data = ("granary pypi ".repeat(100)).substring(0, 1000).getBytes(StandardCharsets.US_ASCII);
        assertThat(Blake2b.hex256(data)).isEqualTo("d3209db634d8f89f82a85565d939338939fd21110d412aad28f3a7c877d2d4d7");
        assertThat(Blake2b.hex(data, 64)).startsWith("e0034c44f565a0c72fd7bad30240b9bd");

        Blake2b incremental = new Blake2b(32);
        int[] chunks = {1, 127, 128, 129, 300, 315};
        int offset = 0;
        for (int chunk : chunks) {
            incremental.update(data, offset, chunk);
            offset += chunk;
        }
        assertThat(offset).isEqualTo(1000);
        assertThat(java.util.HexFormat.of().formatHex(incremental.digest()))
                .isEqualTo("d3209db634d8f89f82a85565d939338939fd21110d412aad28f3a7c877d2d4d7");
    }

    @Test
    void refusesBadLengthsAndReuse() {
        assertThatThrownBy(() -> new Blake2b(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Blake2b(65)).isInstanceOf(IllegalArgumentException.class);
        Blake2b b = new Blake2b(32);
        b.digest();
        assertThatThrownBy(b::digest).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> b.update(new byte[1])).isInstanceOf(IllegalStateException.class);
    }
}
