package org.falatia.emotes.plugin.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class PacketWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public PacketWriter varInt(int value) {
        while ((value & ~0x7F) != 0) {
            this.out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        this.out.write(value);
        return this;
    }

    public PacketWriter integer(int value) {
        this.out.write(value >>> 24);
        this.out.write(value >>> 16);
        this.out.write(value >>> 8);
        this.out.write(value);
        return this;
    }

    public PacketWriter longValue(long value) {
        integer((int) (value >>> 32));
        integer((int) value);
        return this;
    }

    public PacketWriter doubleValue(double value) {
        return longValue(Double.doubleToLongBits(value));
    }

    public PacketWriter floatValue(float value) {
        return integer(Float.floatToIntBits(value));
    }

    public PacketWriter uuid(UUID value) {
        longValue(value.getMostSignificantBits());
        longValue(value.getLeastSignificantBits());
        return this;
    }

    public PacketWriter string(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        varInt(bytes.length);
        this.out.writeBytes(bytes);
        return this;
    }

    public PacketWriter raw(byte[] bytes) {
        this.out.writeBytes(bytes);
        return this;
    }

    public PacketWriter byteArray(byte[] bytes, int offset, int length) {
        varInt(length);
        this.out.write(bytes, offset, length);
        return this;
    }

    public PacketWriter bool(boolean value) {
        this.out.write(value ? 1 : 0);
        return this;
    }

    public byte[] bytes() {
        return this.out.toByteArray();
    }
}
