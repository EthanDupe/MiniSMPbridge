package com.minismp.bridge.translator;

import com.minismp.bridge.util.JavaBinary;
import com.minismp.bridge.util.McpeBinary;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Lightweight, zero-cache Java 1.12.2 <-> MCPE 1.1.5 block & chunk translator.
 * Streams directly from Java ChunkData (0x20) to MCPE FullChunkDataPacket (0x3a).
 */
public final class BlockTranslator {

    private static final int[] JAVA_TO_MCPE_ID = new int[256];

    static {
        for (int i = 0; i < JAVA_TO_MCPE_ID.length; i++) {
            JAVA_TO_MCPE_ID[i] = i;
        }
        // Key differences between Java 1.12.2 and MCPE 1.1.5 block IDs
        JAVA_TO_MCPE_ID[36] = 250;  // Moving piston
        JAVA_TO_MCPE_ID[95] = 241;  // Stained glass
        JAVA_TO_MCPE_ID[125] = 157; // Double wooden slab
        JAVA_TO_MCPE_ID[126] = 158; // Wooden slab
        JAVA_TO_MCPE_ID[157] = 126; // Activator rail
        JAVA_TO_MCPE_ID[158] = 125; // Dropper
        JAVA_TO_MCPE_ID[166] = 95;  // Barrier -> Invisible Bedrock
        JAVA_TO_MCPE_ID[168] = 168; // Prismarine
        JAVA_TO_MCPE_ID[188] = 85;  // Spruce fence -> Fence
        JAVA_TO_MCPE_ID[189] = 85;  // Birch fence
        JAVA_TO_MCPE_ID[190] = 85;  // Jungle fence
        JAVA_TO_MCPE_ID[191] = 85;  // Dark oak fence
        JAVA_TO_MCPE_ID[192] = 85;  // Acacia fence
        JAVA_TO_MCPE_ID[198] = 208; // End rod
        JAVA_TO_MCPE_ID[199] = 240; // Chorus plant
        JAVA_TO_MCPE_ID[200] = 240; // Chorus flower
        JAVA_TO_MCPE_ID[202] = 201; // Purpur pillar
        JAVA_TO_MCPE_ID[207] = 244; // Beetroot
        JAVA_TO_MCPE_ID[208] = 198; // Grass path
        JAVA_TO_MCPE_ID[210] = 188; // Repeating command block
        JAVA_TO_MCPE_ID[211] = 189; // Chain command block
        JAVA_TO_MCPE_ID[212] = 207; // Frosted ice
        JAVA_TO_MCPE_ID[218] = 251; // Observer
        JAVA_TO_MCPE_ID[251] = 236; // Concrete
        JAVA_TO_MCPE_ID[252] = 237; // Concrete powder
        JAVA_TO_MCPE_ID[255] = 252; // Structure block
    }

    private BlockTranslator() {
    }

    public static int translateBlockId(int javaId) {
        if (javaId >= 0 && javaId < JAVA_TO_MCPE_ID.length) {
            return JAVA_TO_MCPE_ID[javaId];
        }
        return 1; // Stone fallback for unknown blocks
    }

    /**
     * Converts Java 1.12.2 ChunkData payload (after chunkX, chunkZ, groundUp, primaryBitMask, dataSize)
     * into MCPE 1.1.5 FullChunkDataPacket payload bytes.
     */
    public static byte[] translateJavaChunkToMcpe(ByteBuf javaData, int primaryBitMask, boolean groundUp, boolean hasSkyLight) {
        // Count highest non-empty section
        int sectionCount = 0;
        for (int s = 0; s < 16; s++) {
            if ((primaryBitMask & (1 << s)) != 0) {
                sectionCount = s + 1;
            }
        }
        if (sectionCount == 0) {
            sectionCount = 1;
        }

        ByteBuf out = Unpooled.buffer(sectionCount * 10241 + 780);
        try {
            out.writeByte(sectionCount);

            byte[] emptyBlocks = new byte[4096];
            byte[] emptyNibbles = new byte[2048];
            byte[] fullSkyLight = new byte[2048];
            java.util.Arrays.fill(fullSkyLight, (byte) 0xFF);

            for (int s = 0; s < sectionCount; s++) {
                out.writeByte(0); // MCPE 1.1.x subchunk storage version 0

                if ((primaryBitMask & (1 << s)) == 0) {
                    out.writeBytes(emptyBlocks);
                    out.writeBytes(emptyNibbles);
                    out.writeBytes(fullSkyLight);
                    out.writeBytes(emptyNibbles);
                    continue;
                }

                int bitsPerBlock = javaData.readUnsignedByte();
                int paletteLen = JavaBinary.readVarInt(javaData);
                int[] palette = null;
                if (bitsPerBlock <= 8 && paletteLen > 0) {
                    palette = new int[paletteLen];
                    for (int i = 0; i < paletteLen; i++) {
                        palette[i] = JavaBinary.readVarInt(javaData);
                    }
                }

                int dataArrayLen = JavaBinary.readVarInt(javaData);
                long[] dataArray = new long[dataArrayLen];
                for (int i = 0; i < dataArrayLen; i++) {
                    dataArray[i] = javaData.readLong();
                }

                byte[] mcpeBlocks = new byte[4096];
                byte[] mcpeMeta = new byte[2048];
                long mask = (1L << bitsPerBlock) - 1L;

                // Java stores YZX; MCPE stores XZY
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int javaIndex = (y << 8) | (z << 4) | x;
                            int bitIndex = javaIndex * bitsPerBlock;
                            int longIndex = bitIndex >>> 6;
                            int startBit = bitIndex & 63;

                            int value;
                            if (longIndex < dataArray.length) {
                                long word = dataArray[longIndex] >>> startBit;
                                if (startBit + bitsPerBlock > 64 && longIndex + 1 < dataArray.length) {
                                    word |= dataArray[longIndex + 1] << (64 - startBit);
                                }
                                value = (int) (word & mask);
                            } else {
                                value = 0;
                            }

                            int state = (palette != null && value >= 0 && value < palette.length) ? palette[value] : value;
                            int blockId = translateBlockId(state >>> 4);
                            int blockData = state & 0xF;

                            int mcpeIndex = (x << 8) | (z << 4) | y;
                            mcpeBlocks[mcpeIndex] = (byte) blockId;

                            int nibbleIdx = mcpeIndex >>> 1;
                            if ((mcpeIndex & 1) == 0) {
                                mcpeMeta[nibbleIdx] = (byte) ((mcpeMeta[nibbleIdx] & 0xF0) | (blockData & 0xF));
                            } else {
                                mcpeMeta[nibbleIdx] = (byte) ((mcpeMeta[nibbleIdx] & 0x0F) | ((blockData & 0xF) << 4));
                            }
                        }
                    }
                }

                // Read Java blockLight (2048 bytes YZX) & skyLight (2048 bytes YZX)
                byte[] javaBlockLight = new byte[2048];
                if (javaData.readableBytes() >= 2048) {
                    javaData.readBytes(javaBlockLight);
                }
                byte[] javaSkyLight = new byte[2048];
                if (hasSkyLight && javaData.readableBytes() >= 2048) {
                    javaData.readBytes(javaSkyLight);
                } else {
                    java.util.Arrays.fill(javaSkyLight, (byte) 0xFF);
                }

                byte[] mcpeSkyLight = convertYzxNibblesToXzy(javaSkyLight);
                byte[] mcpeBlockLight = convertYzxNibblesToXzy(javaBlockLight);

                out.writeBytes(mcpeBlocks);
                out.writeBytes(mcpeMeta);
                out.writeBytes(mcpeSkyLight);
                out.writeBytes(mcpeBlockLight);
            }

            // Heightmap: 256 LShorts (512 bytes)
            for (int i = 0; i < 256; i++) {
                out.writeShortLE(64);
            }

            // Biomes: 256 bytes
            if (groundUp && javaData.readableBytes() >= 256) {
                byte[] biomes = new byte[256];
                javaData.readBytes(biomes);
                out.writeBytes(biomes);
            } else {
                out.writeZero(256);
            }

            out.writeByte(0); // Border blocks
            McpeBinary.writeVarInt(out, 0); // Extra data count

            byte[] result = new byte[out.readableBytes()];
            out.readBytes(result);
            return result;
        } finally {
            out.release();
        }
    }

    private static byte[] convertYzxNibblesToXzy(byte[] yzx) {
        byte[] xzy = new byte[2048];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int yzxIdx = (y << 8) | (z << 4) | x;
                    int val = (yzxIdx & 1) == 0 ? (yzx[yzxIdx >>> 1] & 0xF) : ((yzx[yzxIdx >>> 1] >>> 4) & 0xF);
                    int xzyIdx = (x << 8) | (z << 4) | y;
                    if ((xzyIdx & 1) == 0) {
                        xzy[xzyIdx >>> 1] = (byte) ((xzy[xzyIdx >>> 1] & 0xF0) | val);
                    } else {
                        xzy[xzyIdx >>> 1] = (byte) ((xzy[xzyIdx >>> 1] & 0x0F) | (val << 4));
                    }
                }
            }
        }
        return xzy;
    }
}
