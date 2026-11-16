package com.minismp.bridge.util;

import com.minismp.bridge.mcpe.McpeProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Lightweight MCPE 1.1.5 binary encoding/decoding helpers using Netty ByteBuf
 * and thread-local 8KB scratch buffers to keep GC pressure low on 1 GB RAM hosts.
 */
public final class McpeBinary {

    private static final int SCRATCH_SIZE = 8192;
    private static final ThreadLocal<byte[]> SCRATCH = ThreadLocal.withInitial(() -> new byte[SCRATCH_SIZE]);

    private McpeBinary() {
    }

    public static long readUnsignedVarInt(ByteBuf buf) {
        long value = 0;
        int size = 0;
        int b;
        while (((b = buf.readUnsignedByte()) & 0x80) == 0x80) {
            value |= (long) (b & 0x7F) << (size++ * 7);
            if (size >= 5) {
                throw new IllegalArgumentException("VarInt too big");
            }
        }
        return value | ((long) (b & 0x7F) << (size * 7));
    }

    public static void writeUnsignedVarInt(ByteBuf buf, long value) {
        while ((value & ~0x7FL) != 0) {
            buf.writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.writeByte((int) (value & 0x7F));
    }

    public static int readVarInt(ByteBuf buf) {
        long raw = readUnsignedVarInt(buf);
        return (int) (raw >>> 1) ^ -(int) (raw & 1);
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        long zigZag = ((long) value << 1) ^ (value >> 31);
        writeUnsignedVarInt(buf, zigZag & 0xFFFFFFFFL);
    }

    public static long readUnsignedVarLong(ByteBuf buf) {
        long value = 0;
        int size = 0;
        int b;
        while (((b = buf.readUnsignedByte()) & 0x80) == 0x80) {
            value |= (long) (b & 0x7F) << (size++ * 7);
            if (size >= 10) {
                throw new IllegalArgumentException("VarLong too big");
            }
        }
        return value | ((long) (b & 0x7F) << (size * 7));
    }

    public static void writeUnsignedVarLong(ByteBuf buf, long value) {
        while ((value & ~0x7FL) != 0) {
            buf.writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.writeByte((int) (value & 0x7F));
    }

    public static long readVarLong(ByteBuf buf) {
        long raw = readUnsignedVarLong(buf);
        return (raw >>> 1) ^ -(raw & 1);
    }

    public static void writeVarLong(ByteBuf buf, long value) {
        long zigZag = (value << 1) ^ (value >> 63);
        writeUnsignedVarLong(buf, zigZag);
    }

    public static byte[] readByteArray(ByteBuf buf) {
        int len = (int) readUnsignedVarInt(buf);
        if (len < 0 || len > buf.readableBytes()) {
            throw new IndexOutOfBoundsException("Invalid byte array length: " + len);
        }
        byte[] out = new byte[len];
        buf.readBytes(out);
        return out;
    }

    public static void writeByteArray(ByteBuf buf, byte[] bytes) {
        writeUnsignedVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    public static String readString(ByteBuf buf) {
        int len = (int) readUnsignedVarInt(buf);
        if (len < 0 || len > buf.readableBytes()) {
            throw new IndexOutOfBoundsException("Invalid string length: " + len);
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeString(ByteBuf buf, String str) {
        byte[] bytes = (str == null ? "" : str).getBytes(StandardCharsets.UTF_8);
        writeUnsignedVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    public static void writeBlockCoords(ByteBuf buf, int x, int y, int z) {
        writeVarInt(buf, x);
        writeUnsignedVarInt(buf, y & 0xFFFFFFFFL);
        writeVarInt(buf, z);
    }

    public static int[] readBlockCoords(ByteBuf buf) {
        int x = readVarInt(buf);
        int y = (int) readUnsignedVarInt(buf);
        int z = readVarInt(buf);
        return new int[]{x, y, z};
    }

    public static void writeVector3f(ByteBuf buf, float x, float y, float z) {
        buf.writeFloatLE(x);
        buf.writeFloatLE(y);
        buf.writeFloatLE(z);
    }

    public static float[] readVector3f(ByteBuf buf) {
        return new float[]{buf.readFloatLE(), buf.readFloatLE(), buf.readFloatLE()};
    }

    public static void writeUUID(ByteBuf buf, UUID uuid) {
        buf.writeLongLE(uuid.getMostSignificantBits());
        buf.writeLongLE(uuid.getLeastSignificantBits());
    }

    public static byte[] inflate(byte[] compressed, int maxOutputBytes) throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(compressed.length * 2, 16384));
            byte[] scratch = SCRATCH.get();
            int total = 0;
            while (!inflater.finished()) {
                int count = inflater.inflate(scratch);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                } else {
                    total += count;
                    if (total > maxOutputBytes) {
                        throw new DataFormatException("Inflated payload exceeds limit: " + maxOutputBytes);
                    }
                    out.write(scratch, 0, count);
                }
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    public static byte[] deflate(byte[] raw, int level) {
        Deflater deflater = new Deflater(level);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(raw.length, 8192));
            byte[] scratch = SCRATCH.get();
            while (!deflater.finished()) {
                int count = deflater.deflate(scratch);
                if (count > 0) {
                    out.write(scratch, 0, count);
                }
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * Wraps a single encoded MCPE packet (already containing its packet ID byte)
     * into a 0xfe BatchPacket payload ready for RakNet transmission.
     */
    public static ByteBuf wrapBatch(ByteBuf singlePacket, int compressionLevel) {
        int len = singlePacket.readableBytes();
        ByteBuf framed = Unpooled.buffer(len + 5);
        try {
            writeUnsignedVarInt(framed, len);
            framed.writeBytes(singlePacket, singlePacket.readerIndex(), len);
            byte[] raw = new byte[framed.readableBytes()];
            framed.readBytes(raw);
            byte[] compressed = deflate(raw, compressionLevel);
            ByteBuf out = Unpooled.buffer(1 + compressed.length);
            out.writeByte(McpeProtocol.BATCH_PACKET);
            out.writeBytes(compressed);
            return out;
        } finally {
            framed.release();
        }
    }
}
