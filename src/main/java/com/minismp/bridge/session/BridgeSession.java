package com.minismp.bridge.session;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minismp.bridge.config.BridgeConfig;
import com.minismp.bridge.java.JavaPacketCodec;
import com.minismp.bridge.mcpe.McpeProtocol;
import com.minismp.bridge.translator.BlockTranslator;
import com.minismp.bridge.util.JavaBinary;
import com.minismp.bridge.util.McpeBinary;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Bridges a single Minecraft 3DS 1.10 (MCPE 1.1.5 / protocol 113) RakNet session
 * to the local PaperSpigot 1.12.2 TCP server (protocol 340).
 */
public final class BridgeSession {

    private static final Gson GSON = new Gson();
    private static final float EYE_HEIGHT = 1.62f;

    private enum JavaState {
        HANDSHAKING, LOGIN, PLAY
    }

    private final RakChildChannel rakChannel;
    private final EventLoopGroup tcpGroup;
    private final BridgeConfig config;
    private final Logger logger;
    private final Runnable onClosed;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Channel javaChannel;
    private volatile JavaState javaState = JavaState.HANDSHAKING;

    private String username = "3DSPlayer";
    private UUID uuid = UUID.randomUUID();
    private int entityId = 1;
    private int dimension = 0; // 0 = Overworld, -1 = Nether, 1 = End
    private int gamemode = 0;

    private volatile float posX = 0.0f;
    private volatile float posY = 64.0f;
    private volatile float posZ = 0.0f;
    private volatile float yaw = 0.0f;
    private volatile float pitch = 0.0f;
    private volatile boolean spawned = false;

    public BridgeSession(RakChildChannel rakChannel, EventLoopGroup tcpGroup,
                         BridgeConfig config, Logger logger, Runnable onClosed) {
        this.rakChannel = rakChannel;
        this.tcpGroup = tcpGroup;
        this.config = config;
        this.logger = logger;
        this.onClosed = onClosed;
    }

    public String getUsername() {
        return username;
    }

    public void handleRakMessage(ByteBuf content) {
        if (closed.get() || !content.isReadable()) {
            return;
        }
        int header = content.readUnsignedByte();
        if (header == 0x00) {
            // RakNet connected ping
            return;
        }
        if (header == McpeProtocol.BATCH_PACKET) {
            byte[] compressed = new byte[content.readableBytes()];
            content.readBytes(compressed);
            byte[] inflated;
            try {
                inflated = McpeBinary.inflate(compressed, 4 * 1024 * 1024);
            } catch (Exception e) {
                if (config.isDebugPackets()) {
                    logger.warning("[MiniSMPBridge] Failed to inflate 3DS batch from " + username + ": " + e.getMessage());
                }
                return;
            }
            ByteBuf batchBuf = Unpooled.wrappedBuffer(inflated);
            try {
                while (batchBuf.isReadable()) {
                    int pktLen = (int) McpeBinary.readUnsignedVarInt(batchBuf);
                    if (pktLen <= 0 || pktLen > batchBuf.readableBytes()) {
                        break;
                    }
                    ByteBuf pktSlice = batchBuf.readSlice(pktLen);
                    handleMcpePacketSafely(pktSlice);
                }
            } catch (Exception e) {
                if (config.isDebugPackets()) {
                    logger.warning("[MiniSMPBridge] Malformed batch frame from " + username + ": " + e.getMessage());
                }
            } finally {
                batchBuf.release();
            }
        } else {
            // Uncompressed single packet fallback
            content.readerIndex(content.readerIndex() - 1);
            handleMcpePacketSafely(content);
        }
    }

    /**
     * Safely decodes an MCPE 1.1.5 packet.
     * Catches ArrayIndexOutOfBoundsException from malformed 3DS 1.10 AddHangingEntityPacket (0x10)
     * or unknown 3DS packets so they never spam the console or disconnect the player.
     */
    private void handleMcpePacketSafely(ByteBuf buf) {
        if (!buf.isReadable()) {
            return;
        }
        int packetId = buf.readUnsignedByte();
        try {
            switch (packetId) {
                case McpeProtocol.LOGIN_PACKET:
                    handleMcpeLogin(buf);
                    break;
                case McpeProtocol.RESOURCE_PACK_CLIENT_RESPONSE_PACKET:
                    handleResourcePackResponse(buf);
                    break;
                case McpeProtocol.REQUEST_CHUNK_RADIUS_PACKET:
                    handleRequestChunkRadius(buf);
                    break;
                case McpeProtocol.MOVE_PLAYER_PACKET:
                    handleMcpeMovePlayer(buf);
                    break;
                case McpeProtocol.TEXT_PACKET:
                    handleMcpeText(buf);
                    break;
                case McpeProtocol.PLAYER_ACTION_PACKET:
                    handleMcpePlayerAction(buf);
                    break;
                case McpeProtocol.USE_ITEM_PACKET:
                    handleMcpeUseItem(buf);
                    break;
                case McpeProtocol.ANIMATE_PACKET:
                    handleMcpeAnimate(buf);
                    break;
                case McpeProtocol.RESPAWN_PACKET:
                    handleMcpeRespawn();
                    break;
                case McpeProtocol.ADD_HANGING_ENTITY_PACKET:
                    // Known Minecraft 3DS 1.10 bug: malformed AddHangingEntityPacket throws ArrayIndexOutOfBoundsException.
                    // Safely ignore without console spam or disconnecting the player.
                    if (config.isDebugPackets()) {
                        logger.info("[MiniSMPBridge] Ignored 3DS AddHangingEntityPacket (0x10) from " + username);
                    }
                    break;
                default:
                    if (config.isDebugPackets()) {
                        logger.info("[MiniSMPBridge] Ignored unknown/unhandled 3DS packet 0x" + Integer.toHexString(packetId));
                    }
                    break;
            }
        } catch (ArrayIndexOutOfBoundsException | IndexOutOfBoundsException | IllegalArgumentException ex) {
            if (config.isDebugPackets()) {
                logger.warning("[MiniSMPBridge] Safely caught malformed 3DS packet 0x"
                        + Integer.toHexString(packetId) + " from " + username + ": " + ex.getMessage());
            }
        }
    }

    private void handleMcpeLogin(ByteBuf buf) {
        int clientProtocol = buf.readInt();
        buf.readByte(); // gameEdition
        byte[] payload = McpeBinary.readByteArray(buf);
        ByteBuf inner = Unpooled.wrappedBuffer(payload);
        try {
            int chainLen = inner.readIntLE();
            if (chainLen > 0 && chainLen <= inner.readableBytes()) {
                byte[] chainBytes = new byte[chainLen];
                inner.readBytes(chainBytes);
                String chainJson = new String(chainBytes, StandardCharsets.UTF_8);
                JsonObject obj = GSON.fromJson(chainJson, JsonObject.class);
                if (obj != null && obj.has("chain")) {
                    JsonArray chains = obj.getAsJsonArray("chain");
                    for (JsonElement el : chains) {
                        String[] parts = el.getAsString().split("\\.");
                        if (parts.length >= 2) {
                            String decoded = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                            JsonObject token = GSON.fromJson(decoded, JsonObject.class);
                            if (token != null && token.has("extraData")) {
                                JsonObject extra = token.getAsJsonObject("extraData");
                                if (extra.has("displayName")) {
                                    this.username = extra.get("displayName").getAsString();
                                }
                                if (extra.has("identity")) {
                                    this.uuid = UUID.fromString(extra.get("identity").getAsString());
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (config.isDebugPackets()) {
                logger.warning("[MiniSMPBridge] Login JWT parse warning: " + e.getMessage());
            }
        } finally {
            inner.release();
        }

        logger.info("[MiniSMPBridge] 3DS player '" + username + "' connecting (protocol " + clientProtocol + ")");

        // 1. Send PlayStatus LOGIN_SUCCESS
        sendPlayStatus(McpeProtocol.PLAY_STATUS_LOGIN_SUCCESS);

        // 2. Send empty ResourcePacksInfoPacket
        ByteBuf rpInfo = Unpooled.buffer(8);
        rpInfo.writeByte(McpeProtocol.RESOURCE_PACKS_INFO_PACKET);
        rpInfo.writeBoolean(false); // mustAccept
        rpInfo.writeShortLE(0);     // behaviourPackCount
        rpInfo.writeShortLE(0);     // resourcePackCount
        sendMcpePacket(rpInfo, true);
    }

    private void handleResourcePackResponse(ByteBuf buf) {
        byte status = buf.readByte();
        if (status == McpeProtocol.RESOURCE_PACK_STATUS_HAVE_ALL_PACKS) {
            ByteBuf stack = Unpooled.buffer(8);
            stack.writeByte(McpeProtocol.RESOURCE_PACK_STACK_PACKET);
            stack.writeBoolean(false);
            McpeBinary.writeUnsignedVarInt(stack, 0);
            McpeBinary.writeUnsignedVarInt(stack, 0);
            sendMcpePacket(stack, true);
        } else if (status == McpeProtocol.RESOURCE_PACK_STATUS_COMPLETED) {
            connectToJavaServer();
        }
    }

    private void handleRequestChunkRadius(ByteBuf buf) {
        int requested = McpeBinary.readVarInt(buf);
        int granted = Math.max(2, Math.min(requested, config.getMaxViewDistance()));
        ByteBuf out = Unpooled.buffer(8);
        out.writeByte(McpeProtocol.CHUNK_RADIUS_UPDATED_PACKET);
        McpeBinary.writeVarInt(out, granted);
        sendMcpePacket(out, false);
    }

    private void handleMcpeMovePlayer(ByteBuf buf) {
        McpeBinary.readVarLong(buf); // entityRuntimeId
        float[] vec = McpeBinary.readVector3f(buf);
        float p = buf.readFloatLE();
        float y = buf.readFloatLE();
        buf.readFloatLE(); // headYaw
        buf.readByte();    // mode
        boolean onGround = buf.readBoolean();

        this.posX = vec[0];
        this.posY = vec[1] - EYE_HEIGHT;
        this.posZ = vec[2];
        this.yaw = y;
        this.pitch = p;

        if (javaState == JavaState.PLAY && spawned && javaChannel != null && javaChannel.isActive()) {
            ByteBuf pkt = Unpooled.buffer(40);
            JavaBinary.writeVarInt(pkt, 0x0E); // Serverbound PlayerPositionAndLook (1.12.2)
            pkt.writeDouble(this.posX);
            pkt.writeDouble(this.posY);
            pkt.writeDouble(this.posZ);
            pkt.writeFloat(this.yaw);
            pkt.writeFloat(this.pitch);
            pkt.writeBoolean(onGround);
            javaChannel.writeAndFlush(pkt);
        }
    }

    private void handleMcpeText(ByteBuf buf) {
        byte type = buf.readByte();
        if (type == McpeProtocol.TEXT_CHAT) {
            McpeBinary.readString(buf); // source
        }
        String message = McpeBinary.readString(buf);
        if (javaState == JavaState.PLAY && javaChannel != null && javaChannel.isActive() && !message.isEmpty()) {
            ByteBuf pkt = Unpooled.buffer(message.length() + 8);
            JavaBinary.writeVarInt(pkt, 0x02); // Serverbound ChatMessage (1.12.2)
            JavaBinary.writeString(pkt, message.length() > 256 ? message.substring(0, 256) : message);
            javaChannel.writeAndFlush(pkt);
        }
    }

    private void handleMcpePlayerAction(ByteBuf buf) {
        McpeBinary.readVarLong(buf); // entityRuntimeId
        int action = McpeBinary.readVarInt(buf);
        int[] coords = McpeBinary.readBlockCoords(buf);
        int face = McpeBinary.readVarInt(buf);

        if (javaState != JavaState.PLAY || javaChannel == null || !javaChannel.isActive()) {
            return;
        }
        int javaStatus;
        if (action == 0) {
            javaStatus = 0; // START_DESTROY_BLOCK
        } else if (action == 1) {
            javaStatus = 1; // ABORT_DESTROY_BLOCK
        } else if (action == 2) {
            javaStatus = 2; // STOP_DESTROY_BLOCK
        } else {
            return;
        }
        ByteBuf pkt = Unpooled.buffer(16);
        JavaBinary.writeVarInt(pkt, 0x14); // Serverbound PlayerDigging (1.12.2)
        JavaBinary.writeVarInt(pkt, javaStatus);
        JavaBinary.writePosition(pkt, coords[0], coords[1], coords[2]);
        pkt.writeByte(face & 0xFF);
        javaChannel.writeAndFlush(pkt);
    }

    private void handleMcpeUseItem(ByteBuf buf) {
        int[] coords = McpeBinary.readBlockCoords(buf);
        McpeBinary.readUnsignedVarInt(buf); // interactBlockId
        int face = McpeBinary.readVarInt(buf);
        float[] faceVec = McpeBinary.readVector3f(buf);

        if (javaState != JavaState.PLAY || javaChannel == null || !javaChannel.isActive()) {
            return;
        }
        ByteBuf pkt = Unpooled.buffer(28);
        JavaBinary.writeVarInt(pkt, 0x1F); // Serverbound PlayerBlockPlacement (1.12.2)
        JavaBinary.writePosition(pkt, coords[0], coords[1], coords[2]);
        JavaBinary.writeVarInt(pkt, face);
        JavaBinary.writeVarInt(pkt, 0); // Main hand
        pkt.writeFloat(Math.max(0f, Math.min(1f, faceVec[0])));
        pkt.writeFloat(Math.max(0f, Math.min(1f, faceVec[1])));
        pkt.writeFloat(Math.max(0f, Math.min(1f, faceVec[2])));
        javaChannel.writeAndFlush(pkt);
    }

    private void handleMcpeAnimate(ByteBuf buf) {
        int action = McpeBinary.readVarInt(buf);
        if (action == 1 && javaState == JavaState.PLAY && javaChannel != null && javaChannel.isActive()) {
            ByteBuf pkt = Unpooled.buffer(4);
            JavaBinary.writeVarInt(pkt, 0x1D); // Serverbound Animation (1.12.2)
            JavaBinary.writeVarInt(pkt, 0);    // Main hand
            javaChannel.writeAndFlush(pkt);
        }
    }

    private void handleMcpeRespawn() {
        if (javaState == JavaState.PLAY && javaChannel != null && javaChannel.isActive()) {
            ByteBuf pkt = Unpooled.buffer(4);
            JavaBinary.writeVarInt(pkt, 0x03); // Serverbound ClientStatus (1.12.2)
            JavaBinary.writeVarInt(pkt, 0);    // Perform Respawn
            javaChannel.writeAndFlush(pkt);
        }
    }

    private void connectToJavaServer() {
        final JavaPacketCodec.CompressionCodec compressionState = new JavaPacketCodec.CompressionCodec();
        Bootstrap b = new Bootstrap()
                .group(tcpGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 8000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("frameDecoder", new JavaPacketCodec.FrameDecoder());
                        ch.pipeline().addLast("compressionDecoder", compressionState);
                        ch.pipeline().addLast("frameEncoder", new JavaPacketCodec.FrameEncoder());
                        ch.pipeline().addLast("compressionEncoder", new JavaPacketCodec.CompressionEncoder(compressionState, config.getCompressionLevel()));
                        ch.pipeline().addLast("bridgeHandler", new JavaUpstreamHandler(compressionState));
                    }
                });

        ChannelFuture future = b.connect(config.getTargetHost(), config.getTargetPort());
        future.addListener(f -> {
            if (!f.isSuccess()) {
                logger.warning("[MiniSMPBridge] Could not connect 3DS player '" + username + "' to "
                        + config.getTargetHost() + ":" + config.getTargetPort() + " - " + f.cause().getMessage());
                sendDisconnect("§cFailed to connect to Paper server.");
                close();
                return;
            }
            javaChannel = future.channel();
            sendJavaHandshakeAndLogin();
        });
    }

    private void sendJavaHandshakeAndLogin() {
        // 1. Handshake (0x00, protocol 340, nextState=2)
        ByteBuf handshake = Unpooled.buffer(32);
        JavaBinary.writeVarInt(handshake, 0x00);
        JavaBinary.writeVarInt(handshake, 340);
        JavaBinary.writeString(handshake, config.getTargetHost());
        handshake.writeShort(config.getTargetPort());
        JavaBinary.writeVarInt(handshake, 2);
        javaChannel.write(handshake);

        // 2. Login Start (0x00, username)
        ByteBuf loginStart = Unpooled.buffer(32);
        JavaBinary.writeVarInt(loginStart, 0x00);
        JavaBinary.writeString(loginStart, username);
        javaChannel.writeAndFlush(loginStart);
        javaState = JavaState.LOGIN;
    }

    private final class JavaUpstreamHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final JavaPacketCodec.CompressionCodec compressionState;

        JavaUpstreamHandler(JavaPacketCodec.CompressionCodec compressionState) {
            this.compressionState = compressionState;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            if (closed.get() || !msg.isReadable()) {
                return;
            }
            int packetId = JavaBinary.readVarInt(msg);
            if (javaState == JavaState.LOGIN) {
                handleJavaLoginPacket(packetId, msg, compressionState);
            } else if (javaState == JavaState.PLAY) {
                handleJavaPlayPacket(packetId, msg);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (config.isDebugPackets()) {
                logger.warning("[MiniSMPBridge] Java upstream error for " + username + ": " + cause.getMessage());
            }
            close();
        }
    }

    private void handleJavaLoginPacket(int packetId, ByteBuf msg, JavaPacketCodec.CompressionCodec compressionState) {
        if (packetId == 0x03) {
            int threshold = JavaBinary.readVarInt(msg);
            compressionState.setThreshold(threshold);
        } else if (packetId == 0x02) {
            JavaBinary.readString(msg); // UUID string
            JavaBinary.readString(msg); // Username
            javaState = JavaState.PLAY;
        } else if (packetId == 0x00) {
            String reason = JavaBinary.jsonChatToLegacy(JavaBinary.readString(msg));
            sendDisconnect(reason);
            close();
        }
    }

    private void handleJavaPlayPacket(int packetId, ByteBuf msg) {
        switch (packetId) {
            case 0x1F: { // KeepAlive (Clientbound 1.12.2)
                long id = msg.readLong();
                ByteBuf reply = Unpooled.buffer(12);
                JavaBinary.writeVarInt(reply, 0x0B); // KeepAlive (Serverbound 1.12.2)
                reply.writeLong(id);
                javaChannel.writeAndFlush(reply);
                break;
            }
            case 0x23: { // JoinGame
                this.entityId = msg.readInt();
                this.gamemode = msg.readUnsignedByte() & 0x0F;
                this.dimension = msg.readInt();
                int difficulty = msg.readUnsignedByte();
                sendMcpeStartGame(difficulty);
                break;
            }
            case 0x2F: { // PlayerPositionAndLook (Clientbound 1.12.2)
                double x = msg.readDouble();
                double y = msg.readDouble();
                double z = msg.readDouble();
                float yRot = msg.readFloat();
                float xRot = msg.readFloat();
                byte flags = msg.readByte();
                int teleportId = JavaBinary.readVarInt(msg);

                this.posX = (flags & 0x01) != 0 ? this.posX + (float) x : (float) x;
                this.posY = (flags & 0x02) != 0 ? this.posY + (float) y : (float) y;
                this.posZ = (flags & 0x04) != 0 ? this.posZ + (float) z : (float) z;
                this.yaw = (flags & 0x08) != 0 ? this.yaw + yRot : yRot;
                this.pitch = (flags & 0x10) != 0 ? this.pitch + xRot : xRot;

                // Acknowledge teleport to Paper
                ByteBuf confirm = Unpooled.buffer(8);
                JavaBinary.writeVarInt(confirm, 0x00); // TeleportConfirm
                JavaBinary.writeVarInt(confirm, teleportId);
                javaChannel.writeAndFlush(confirm);

                if (!spawned) {
                    spawned = true;
                    sendPlayStatus(McpeProtocol.PLAY_STATUS_PLAYER_SPAWN);
                }
                sendMcpeMovePlayer(this.entityId, this.posX, this.posY + EYE_HEIGHT, this.posZ, this.yaw, this.pitch, (byte) 2);
                break;
            }
            case 0x20: { // ChunkData
                int chunkX = msg.readInt();
                int chunkZ = msg.readInt();
                boolean groundUp = msg.readBoolean();
                int primaryBitMask = JavaBinary.readVarInt(msg);
                int dataSize = JavaBinary.readVarInt(msg);
                if (dataSize > 0 && msg.readableBytes() >= dataSize) {
                    ByteBuf chunkSlice = msg.readSlice(dataSize);
                    byte[] mcpeChunk = BlockTranslator.translateJavaChunkToMcpe(chunkSlice, primaryBitMask, groundUp, dimension == 0);
                    ByteBuf out = Unpooled.buffer(16 + mcpeChunk.length);
                    out.writeByte(McpeProtocol.FULL_CHUNK_DATA_PACKET);
                    McpeBinary.writeVarInt(out, chunkX);
                    McpeBinary.writeVarInt(out, chunkZ);
                    McpeBinary.writeByteArray(out, mcpeChunk);
                    sendMcpePacket(out, false);
                }
                break;
            }
            case 0x0B: { // BlockChange
                int[] pos = JavaBinary.readPosition(msg);
                int state = JavaBinary.readVarInt(msg);
                sendMcpeUpdateBlock(pos[0], pos[1], pos[2], BlockTranslator.translateBlockId(state >>> 4), state & 0xF);
                break;
            }
            case 0x10: { // MultiBlockChange
                int chunkX = msg.readInt();
                int chunkZ = msg.readInt();
                int count = JavaBinary.readVarInt(msg);
                for (int i = 0; i < count && msg.readableBytes() >= 3; i++) {
                    int horiz = msg.readUnsignedByte();
                    int y = msg.readUnsignedByte();
                    int state = JavaBinary.readVarInt(msg);
                    int x = (chunkX << 4) + ((horiz >>> 4) & 0xF);
                    int z = (chunkZ << 4) + (horiz & 0xF);
                    sendMcpeUpdateBlock(x, y, z, BlockTranslator.translateBlockId(state >>> 4), state & 0xF);
                }
                break;
            }
            case 0x0F: { // ChatMessage (Clientbound)
                String json = JavaBinary.readString(msg);
                String text = JavaBinary.jsonChatToLegacy(json);
                if (!text.isEmpty()) {
                    ByteBuf out = Unpooled.buffer(text.length() + 8);
                    out.writeByte(McpeProtocol.TEXT_PACKET);
                    out.writeByte(McpeProtocol.TEXT_RAW);
                    McpeBinary.writeString(out, text);
                    sendMcpePacket(out, false);
                }
                break;
            }
            case 0x41: { // UpdateHealth
                float health = msg.readFloat();
                ByteBuf out = Unpooled.buffer(8);
                out.writeByte(McpeProtocol.SET_HEALTH_PACKET);
                McpeBinary.writeUnsignedVarInt(out, Math.max(0, Math.round(health)));
                sendMcpePacket(out, false);
                break;
            }
            case 0x35: { // Respawn
                this.dimension = msg.readInt();
                ByteBuf out = Unpooled.buffer(16);
                out.writeByte(McpeProtocol.RESPAWN_PACKET);
                McpeBinary.writeVector3f(out, posX, posY + EYE_HEIGHT, posZ);
                sendMcpePacket(out, true);
                break;
            }
            case 0x47: { // TimeUpdate
                msg.readLong(); // worldAge
                long timeOfDay = msg.readLong();
                ByteBuf out = Unpooled.buffer(8);
                out.writeByte(McpeProtocol.SET_TIME_PACKET);
                McpeBinary.writeVarInt(out, (int) (Math.abs(timeOfDay) % 24000L));
                out.writeBoolean(timeOfDay >= 0);
                sendMcpePacket(out, false);
                break;
            }
            case 0x1A: { // Disconnect (Play)
                String reason = JavaBinary.jsonChatToLegacy(JavaBinary.readString(msg));
                sendDisconnect(reason);
                close();
                break;
            }
            default:
                break;
        }
    }

    private void sendMcpeStartGame(int difficulty) {
        ByteBuf out = Unpooled.buffer(128);
        out.writeByte(McpeProtocol.START_GAME_PACKET);
        McpeBinary.writeVarLong(out, entityId);
        McpeBinary.writeVarLong(out, entityId);
        McpeBinary.writeVarInt(out, gamemode);
        McpeBinary.writeVector3f(out, posX, posY + EYE_HEIGHT, posZ);
        out.writeFloatLE(pitch);
        out.writeFloatLE(yaw);
        McpeBinary.writeVarInt(out, 0); // seed
        McpeBinary.writeVarInt(out, dimension == -1 ? 1 : (dimension == 1 ? 2 : 0));
        McpeBinary.writeVarInt(out, 1); // infinite generator
        McpeBinary.writeVarInt(out, gamemode);
        McpeBinary.writeVarInt(out, difficulty);
        McpeBinary.writeBlockCoords(out, (int) posX, (int) posY, (int) posZ);
        out.writeBoolean(true); // hasAchievementsDisabled
        McpeBinary.writeVarInt(out, -1); // dayCycleStopTime
        out.writeBoolean(false); // eduMode
        out.writeFloatLE(0.0f);  // rainLevel
        out.writeFloatLE(0.0f);  // lightningLevel
        out.writeBoolean(true);  // commandsEnabled
        out.writeBoolean(false); // isTexturePacksRequired
        McpeBinary.writeUnsignedVarInt(out, 0); // ruleDatas
        McpeBinary.writeString(out, "");
        McpeBinary.writeString(out, "MiniSMP");
        McpeBinary.writeString(out, "");
        sendMcpePacket(out, true);

        ByteBuf radius = Unpooled.buffer(8);
        radius.writeByte(McpeProtocol.CHUNK_RADIUS_UPDATED_PACKET);
        McpeBinary.writeVarInt(radius, config.getMaxViewDistance());
        sendMcpePacket(radius, false);
    }

    private void sendMcpeMovePlayer(long runtimeId, float x, float y, float z, float yaw, float pitch, byte mode) {
        ByteBuf out = Unpooled.buffer(48);
        out.writeByte(McpeProtocol.MOVE_PLAYER_PACKET);
        McpeBinary.writeVarLong(out, runtimeId);
        McpeBinary.writeVector3f(out, x, y, z);
        out.writeFloatLE(pitch);
        out.writeFloatLE(yaw);
        out.writeFloatLE(yaw);
        out.writeByte(mode);
        out.writeBoolean(true);
        McpeBinary.writeVarLong(out, 0);
        sendMcpePacket(out, false);
    }

    private void sendMcpeUpdateBlock(int x, int y, int z, int blockId, int blockData) {
        ByteBuf out = Unpooled.buffer(24);
        out.writeByte(McpeProtocol.UPDATE_BLOCK_PACKET);
        McpeBinary.writeBlockCoords(out, x, y, z);
        McpeBinary.writeUnsignedVarInt(out, blockId);
        McpeBinary.writeUnsignedVarInt(out, (0x0b << 4) | (blockData & 0x0f));
        sendMcpePacket(out, false);
    }

    private void sendPlayStatus(int status) {
        ByteBuf out = Unpooled.buffer(8);
        out.writeByte(McpeProtocol.PLAY_STATUS_PACKET);
        out.writeInt(status);
        sendMcpePacket(out, true);
    }

    private void sendDisconnect(String reason) {
        if (!rakChannel.isActive()) {
            return;
        }
        ByteBuf out = Unpooled.buffer(64);
        out.writeByte(McpeProtocol.DISCONNECT_PACKET);
        out.writeBoolean(false);
        McpeBinary.writeString(out, reason == null ? "Disconnected" : reason);
        sendMcpePacket(out, true);
    }

    private void sendMcpePacket(ByteBuf packet, boolean immediate) {
        if (closed.get() || !rakChannel.isActive()) {
            packet.release();
            return;
        }
        ByteBuf batched = McpeBinary.wrapBatch(packet, config.getCompressionLevel());
        packet.release();
        rakChannel.writeAndFlush(new RakMessage(
                batched,
                RakReliability.RELIABLE_ORDERED,
                immediate ? RakPriority.IMMEDIATE : RakPriority.NORMAL,
                0
        ));
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (javaChannel != null && javaChannel.isOpen()) {
            javaChannel.close();
        }
        if (rakChannel.isOpen()) {
            rakChannel.close();
        }
        onClosed.run();
    }
}
