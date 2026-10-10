package dev.granary.format.hex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HexProtobufTest {

    @Test
    void optionalScalarsAreLeftOutWhenDefaultRequiredOnesAreNot() {
        byte[] bytes = new Protobuf.Writer()
                .varint(1, 0)
                .bool(2, false)
                .string(3, "")
                .string(4, null)
                .bytes(5, new byte[0])
                .packedInts(6, List.of())
                .toByteArray();
        assertThat(bytes).isEmpty();

        byte[] required = new Protobuf.Writer()
                .requiredVarint(1, 0)
                .requiredString(2, "")
                .requiredString(3, null)
                .requiredBytes(4, null)
                .toByteArray();
        assertThat(required).containsExactly(0x08, 0x00, 0x12, 0x00, 0x1A, 0x00, 0x22, 0x00);
    }

    @Test
    void varintsUseSevenBitGroupsAndNegativeIntsTakeTenBytes() {
        assertThat(new Protobuf.Writer().requiredVarint(1, 300).toByteArray()).containsExactly(0x08, 0xAC, 0x02);
        byte[] negative = new Protobuf.Writer().requiredVarint(1, -1).toByteArray();
        assertThat(negative).hasSize(11);
        Protobuf.Reader reader = new Protobuf.Reader(negative);
        assertThat(reader.nextField()).isEqualTo(1);
        assertThat(reader.varintValue()).isEqualTo(-1L);
        assertThat(new Protobuf.Writer().bool(7, true).toByteArray()).containsExactly(0x38, 0x01);
    }

    @Test
    void largeFieldNumbersAndLongValuesRoundTrip() {
        byte[] bytes = new Protobuf.Writer()
                .requiredVarint(100_000, Long.MAX_VALUE)
                .string(2047, "ünïcode ✓")
                .toByteArray();
        Protobuf.Reader reader = new Protobuf.Reader(bytes);
        assertThat(reader.nextField()).isEqualTo(100_000);
        assertThat(reader.varintValue()).isEqualTo(Long.MAX_VALUE);
        assertThat(reader.nextField()).isEqualTo(2047);
        assertThat(reader.readString()).isEqualTo("ünïcode ✓");
        assertThat(reader.hasNext()).isFalse();
    }

    @Test
    void repeatedIntsAreReadPackedOrUnpacked() {
        byte[] packed = new Protobuf.Writer().packedInts(3, List.of(0, 1, 300)).toByteArray();
        assertThat(packed).containsExactly(0x1A, 0x04, 0x00, 0x01, 0xAC, 0x02);
        byte[] unpacked = new Protobuf.Writer().requiredVarint(3, 0).requiredVarint(3, 1).requiredVarint(3, 300).toByteArray();
        for (byte[] bytes : List.of(packed, unpacked)) {
            List<Integer> into = new ArrayList<>();
            Protobuf.Reader reader = new Protobuf.Reader(bytes);
            while (reader.hasNext()) {
                assertThat(reader.nextField()).isEqualTo(3);
                reader.readInts(into);
            }
            assertThat(into).containsExactly(0, 1, 300);
        }
    }

    @Test
    void embeddedMessagesAreBounded() {
        byte[] inner = new Protobuf.Writer().requiredString(1, "inner").toByteArray();
        byte[] outer = new Protobuf.Writer().message(1, inner).requiredString(2, "after").toByteArray();
        Protobuf.Reader reader = new Protobuf.Reader(outer);
        assertThat(reader.nextField()).isEqualTo(1);
        Protobuf.Reader sub = reader.readMessage();
        assertThat(sub.nextField()).isEqualTo(1);
        assertThat(sub.readString()).isEqualTo("inner");
        assertThat(sub.hasNext()).isFalse();
        assertThat(reader.nextField()).isEqualTo(2);
        assertThat(reader.readString()).isEqualTo("after");
    }

    @Test
    void everyWireTypeCanBeSkipped() {
        byte[] bytes = {
            0x08, 0x05,                                     // 1: varint
            0x11, 1, 2, 3, 4, 5, 6, 7, 8,                   // 2: fixed64
            0x1A, 0x02, 'h', 'i',                           // 3: length-delimited
            0x25, 1, 2, 3, 4,                               // 4: fixed32
            0x28, 0x07                                      // 5: varint kept
        };
        Protobuf.Reader reader = new Protobuf.Reader(bytes);
        long kept = -1;
        while (reader.hasNext()) {
            if (reader.nextField() == 5) {
                kept = reader.varintValue();
            } else {
                reader.skip();
            }
        }
        assertThat(kept).isEqualTo(7);
    }

    @Test
    void fixedWidthNumbersAreAcceptedWhereVarintsAreExpected() {
        Protobuf.Reader reader = new Protobuf.Reader(new byte[] {0x0D, 0x02, 0x00, 0x00, 0x00});
        reader.nextField();
        assertThat(reader.varintValue()).isEqualTo(2);
    }

    @Test
    void malformedInputIsRefused() {
        assertThatThrownBy(() -> new Protobuf.Reader(new byte[] {0x00}).nextField())
                .isInstanceOf(Protobuf.MalformedException.class).hasMessageContaining("field number");
        assertThatThrownBy(() -> {
            Protobuf.Reader r = new Protobuf.Reader(new byte[] {0x0B});
            r.nextField();
            r.skip();
        }).isInstanceOf(Protobuf.MalformedException.class).hasMessageContaining("wire type");
        assertThatThrownBy(() -> {
            Protobuf.Reader r = new Protobuf.Reader(new byte[] {0x0A, 0x05, 'a'});
            r.nextField();
            r.readBytes();
        }).isInstanceOf(Protobuf.MalformedException.class).hasMessageContaining("exceeds");
        assertThatThrownBy(() -> {
            Protobuf.Reader r = new Protobuf.Reader(new byte[] {0x08, 0x01});
            r.nextField();
            r.readBytes();
        }).isInstanceOf(Protobuf.MalformedException.class).hasMessageContaining("length-delimited");
        assertThatThrownBy(() -> {
            Protobuf.Reader r = new Protobuf.Reader(new byte[] {0x0A, 0x00});
            r.nextField();
            r.varintValue();
        }).isInstanceOf(Protobuf.MalformedException.class).hasMessageContaining("numeric");
        byte[] endless = new byte[12];
        java.util.Arrays.fill(endless, (byte) 0xFF);
        assertThatThrownBy(() -> new Protobuf.Reader(endless).readVarint())
                .isInstanceOf(Protobuf.MalformedException.class).hasMessageContaining("10 bytes");
        assertThatThrownBy(() -> {
            Protobuf.Reader r = new Protobuf.Reader(new byte[] {0x09, 1, 2});
            r.nextField();
            r.skip();
        }).isInstanceOf(Protobuf.MalformedException.class).hasMessageContaining("truncated");
        assertThat(new Protobuf.MalformedException("x")).isInstanceOf(IllegalArgumentException.class);
    }
}
