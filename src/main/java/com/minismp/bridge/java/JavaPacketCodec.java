package com.minismp.bridge.java;

import com.minismp.bridge.util.JavaBinary;
import com.minismp.bridge.util.McpeBinary;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.List;

/**
 * Netty framing and zlib compression codecs for Java Edition 1.12.2 (protocol 340).
 */
public final class JavaPacketCodec {

    private JavaPacketCodec() {
    }

    public static final class FrameDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            in.markReaderIndex();
            byte[] buf = new byte[3];
            for (int i = 0; i < buf.length; i++) {
                if (!in.isReadable()) {
                    in.resetReaderIndex();
                    return;
                }
                buf[i] = in.readByte();
                if (buf[i] >= 0) {
                    int length = JavaBinary.readVarInt(Unpooled.wrappedBuffer(buf, 0, i + 1));
                    if (length < 0) {
                        throw new IllegalArgumentException("Negative packet length: " + length);
                    }
                    if (in.readableBytes() < length) {
                        in.resetReaderIndex();
                        return;
                    }
                    out.add(in.readRetainedSlice(length));
                    return;
                }
            }
            throw new IllegalArgumentException("VarInt packet length wider than 21 bits");
        }
    }

    public static final class FrameEncoder extends MessageToByteEncoder<ByteBuf> {
        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
            int bodyLen = msg.readableBytes();
            JavaBinary.writeVarInt(out, bodyLen);
            out.writeBytes(msg, msg.readerIndex(), bodyLen);
        }
    }

    public static final class CompressionCodec extends ByteToMessageDecoder {
        private volatile int threshold = -1;

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        public int getThreshold() {
            return threshold;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            if (threshold < 0) {
                out.add(in.readRetainedSlice(in.readableBytes()));
                return;
            }
            int dataLength = JavaBinary.readVarInt(in);
            if (dataLength == 0) {
                out.add(in.readRetainedSlice(in.readableBytes()));
            } else {
                byte[] compressed = new byte[in.readableBytes()];
                in.readBytes(compressed);
                byte[] uncompressed = McpeBinary.inflate(compressed, 8 * 1024 * 1024);
                out.add(Unpooled.wrappedBuffer(uncompressed));
            }
        }
    }

    public static final class CompressionEncoder extends MessageToByteEncoder<ByteBuf> {
        private final CompressionCodec state;
        private final int level;

        public CompressionEncoder(CompressionCodec state, int level) {
            this.state = state;
            this.level = level;
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
            int threshold = state.getThreshold();
            if (threshold < 0) {
                out.writeBytes(msg, msg.readerIndex(), msg.readableBytes());
                return;
            }
            int size = msg.readableBytes();
            if (size < threshold) {
                JavaBinary.writeVarInt(out, 0);
                out.writeBytes(msg, msg.readerIndex(), size);
            } else {
                byte[] raw = new byte[size];
                msg.getBytes(msg.readerIndex(), raw);
                byte[] compressed = McpeBinary.deflate(raw, level);
                JavaBinary.writeVarInt(out, size);
                out.writeBytes(compressed);
            }
        }
    }
}
