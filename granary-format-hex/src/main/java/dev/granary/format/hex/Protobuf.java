package dev.granary.format.hex;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The slice of the protocol buffers wire format the Hex registry documents use: varints, booleans,
 * enums, strings, bytes, packed repeated integers and embedded messages. The registry schemas are
 * proto2: {@code required} fields are always written (the {@code required*} methods), optional
 * scalars only when they carry a value, and readers tolerate both as well as unknown fields.
 */
final class Protobuf {

    static final int WIRE_VARINT = 0;
    static final int WIRE_FIXED64 = 1;
    static final int WIRE_LENGTH = 2;
    static final int WIRE_FIXED32 = 5;

    private Protobuf() {
    }

    /** Thrown for bytes that are not a well-formed message. */
    static final class MalformedException extends IllegalArgumentException {
        MalformedException(String message) {
            super(message);
        }
    }

    /** Appends fields to a message. */
    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        Writer varint(int field, long value) {
            if (value != 0) {
                tag(field, WIRE_VARINT);
                writeVarint(value);
            }
            return this;
        }

        /** A required numeric field (proto2): written even when it is zero. */
        Writer requiredVarint(int field, long value) {
            tag(field, WIRE_VARINT);
            writeVarint(value);
            return this;
        }

        Writer bool(int field, boolean value) {
            return varint(field, value ? 1 : 0);
        }

        /** A required string field (proto2): written even when it is empty. */
        Writer requiredString(int field, String value) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            lengthDelimited(field, bytes, 0, bytes.length);
            return this;
        }

        /** A required bytes field (proto2): written even when it is empty. */
        Writer requiredBytes(int field, byte[] value) {
            byte[] bytes = value == null ? new byte[0] : value;
            lengthDelimited(field, bytes, 0, bytes.length);
            return this;
        }

        Writer string(int field, String value) {
            if (value != null && !value.isEmpty()) {
                bytes(field, value.getBytes(StandardCharsets.UTF_8));
            }
            return this;
        }

        Writer bytes(int field, byte[] value) {
            if (value != null && value.length > 0) {
                lengthDelimited(field, value, 0, value.length);
            }
            return this;
        }

        /** An embedded message is written even when empty: presence carries meaning (a retirement with defaults). */
        Writer message(int field, byte[] encoded) {
            lengthDelimited(field, encoded, 0, encoded.length);
            return this;
        }

        /** A packed repeated int32 field, the proto3 default for repeated scalars. */
        Writer packedInts(int field, Iterable<Integer> values) {
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            for (int v : values) {
                writeVarint(packed, v);
            }
            if (packed.size() > 0) {
                byte[] body = packed.toByteArray();
                lengthDelimited(field, body, 0, body.length);
            }
            return this;
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }

        private void lengthDelimited(int field, byte[] value, int offset, int length) {
            tag(field, WIRE_LENGTH);
            writeVarint(length);
            out.write(value, offset, length);
        }

        private void tag(int field, int wireType) {
            writeVarint(((long) field << 3) | wireType);
        }

        private void writeVarint(long value) {
            writeVarint(out, value);
        }

        private static void writeVarint(ByteArrayOutputStream target, long value) {
            long v = value;
            while ((v & ~0x7FL) != 0) {
                target.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            target.write((int) v);
        }
    }

    /** Walks the fields of one message. Unknown fields are skipped by wire type. */
    static final class Reader {
        private final byte[] data;
        private final int limit;
        private int pos;
        private int wireType;

        Reader(byte[] data) {
            this(data, 0, data.length);
        }

        private Reader(byte[] data, int offset, int length) {
            this.data = data;
            this.pos = offset;
            this.limit = offset + length;
        }

        boolean hasNext() {
            return pos < limit;
        }

        /** Reads the next tag and returns its field number; {@link #wireType()} tells how to read the value. */
        int nextField() {
            long tag = readVarint();
            wireType = (int) (tag & 7);
            long field = tag >>> 3;
            if (field <= 0 || field > Integer.MAX_VALUE) {
                throw new MalformedException("invalid field number " + field);
            }
            return (int) field;
        }

        int wireType() {
            return wireType;
        }

        long readVarint() {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= limit) {
                    throw new MalformedException("truncated varint");
                }
                int b = data[pos++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            throw new MalformedException("varint longer than 10 bytes");
        }

        /** The value of the current field as a varint, whatever its wire type says (lenient for enums and bools). */
        long varintValue() {
            return switch (wireType) {
                case WIRE_VARINT -> readVarint();
                case WIRE_FIXED32 -> fixed(4);
                case WIRE_FIXED64 -> fixed(8);
                default -> throw new MalformedException("expected a numeric field, got wire type " + wireType);
            };
        }

        byte[] readBytes() {
            if (wireType != WIRE_LENGTH) {
                throw new MalformedException("expected a length-delimited field, got wire type " + wireType);
            }
            long length = readVarint();
            if (length < 0 || length > limit - pos) {
                throw new MalformedException("length " + length + " exceeds the message");
            }
            byte[] value = new byte[(int) length];
            System.arraycopy(data, pos, value, 0, (int) length);
            pos += (int) length;
            return value;
        }

        String readString() {
            return new String(readBytes(), StandardCharsets.UTF_8);
        }

        /** The embedded message of the current field. */
        Reader readMessage() {
            if (wireType != WIRE_LENGTH) {
                throw new MalformedException("expected an embedded message, got wire type " + wireType);
            }
            long length = readVarint();
            if (length < 0 || length > limit - pos) {
                throw new MalformedException("length " + length + " exceeds the message");
            }
            Reader sub = new Reader(data, pos, (int) length);
            pos += (int) length;
            return sub;
        }

        /** Reads a repeated int32 field that may be packed (wire type 2) or not (wire type 0). */
        void readInts(java.util.List<Integer> into) {
            if (wireType == WIRE_LENGTH) {
                Reader packed = readMessage();
                while (packed.hasNext()) {
                    into.add((int) packed.readVarint());
                }
            } else {
                into.add((int) varintValue());
            }
        }

        void skip() {
            switch (wireType) {
                case WIRE_VARINT -> readVarint();
                case WIRE_FIXED64 -> fixed(8);
                case WIRE_LENGTH -> readBytes();
                case WIRE_FIXED32 -> fixed(4);
                default -> throw new MalformedException("unsupported wire type " + wireType);
            }
        }

        private long fixed(int bytes) {
            if (limit - pos < bytes) {
                throw new MalformedException("truncated fixed-width field");
            }
            long value = 0;
            for (int i = 0; i < bytes; i++) {
                value |= (long) (data[pos + i] & 0xFF) << (8 * i);
            }
            pos += bytes;
            return value;
        }
    }
}
