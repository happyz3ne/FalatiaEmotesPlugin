package org.falatia.emotes.plugin.net;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

public final class PacketReader {

    private final byte[] bytes;
    private int position;

    public PacketReader(byte[] bytes) {
        this.bytes = bytes;
    }

    private int next() {
        if (this.position >= this.bytes.length) {
            throw new IllegalArgumentException("Packet too short");
        }
        return this.bytes[this.position++] & 0xFF;
    }

    public int varInt() {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = next();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("VarInt too big");
    }

    public int integer() {
        return next() << 24 | next() << 16 | next() << 8 | next();
    }

    public long longValue() {
        return (long) integer() << 32 | integer() & 0xFFFFFFFFL;
    }

    public double doubleValue() {
        return Double.longBitsToDouble(longValue());
    }

    public float floatValue() {
        return Float.intBitsToFloat(integer());
    }

    public UUID uuid() {
        return new UUID(longValue(), longValue());
    }

    public byte[] byteArray(int maxBytes) {
        int length = varInt();
        if (length < 0 || length > maxBytes || this.position + length > this.bytes.length) {
            throw new IllegalArgumentException("Bad array length " + length);
        }
        byte[] value = Arrays.copyOfRange(this.bytes, this.position, this.position + length);
        this.position += length;
        return value;
    }

    public String string(int maxBytes) {
        int length = varInt();
        if (length < 0 || length > maxBytes || this.position + length > this.bytes.length) {
            throw new IllegalArgumentException("Bad string length " + length);
        }
        String value = new String(this.bytes, this.position, length, StandardCharsets.UTF_8);
        this.position += length;
        return value;
    }
}
