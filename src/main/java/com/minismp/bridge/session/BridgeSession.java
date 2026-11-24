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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Bridges a single Minecraft 3DS 1.10 (MCPE 1.1.5 / protocol 113) RakNet session
 * to the local Spigot/Paper 1.12.2 TCP server (protocol 340).
 */
public final class BridgeSession {

    private static final Gson GSON = new Gson();
    private static final float EYE_HEIGHT = 1.62f;
    private static final int MAX_TRACKED_ENTITIES = 256;

    private enum JavaState {
        HANDSHAKING, LOGIN, PLAY
    }

    private static final class TrackedEntity {
        float x, y, z;
        float vx, vy, vz;
        float yaw, pitch;
        boolean isPendingItem;
        boolean spawnedOnMcpe;
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
    private volatile float currentHealth = 20.0f;
    private volatile float currentHunger = 20.0f;
    private volatile float currentMoveSpeed = 0.10f;
    private volatile boolean allowFlight = false;
    private volatile boolean isFlying = false;
    private volatile int selectedHotbarSlot = 0;
    private volatile boolean spawned = false;

    // Active block-break tracking (MCPE sends ACTION_START_BREAK, then ACTION_STOP_BREAK(0,0,0), then RemoveBlockPacket)
    private int lastBreakX = Integer.MIN_VALUE;
    private int lastBreakY = Integer.MIN_VALUE;
    private int lastBreakZ = Integer.MIN_VALUE;
    private int lastBreakFace = 1;

    // Lightweight 36-slot player inventory cache (0..8 = hotbar, 9..35 = main)
    private final int[][] inventory = new int[36][3];

    // Lightweight tracked entities (dropped items, mobs, other players) - accessed on Java Netty event loop
    private final Map<Integer, TrackedEntity> entities = new HashMap<>();

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
            return;
        }
        if (header == McpeProtocol.RAKNET_GAME_PACKET_WRAPPER || header == 0xff) {
            if (content.readableBytes() >= 6) {
                int firstByte = content.getUnsignedByte(content.readerIndex());
                int possibleProtocol = content.getInt(content.readerIndex() + 1);
                if (firstByte == McpeProtocol.LOGIN_PACKET && possibleProtocol >= 90 && possibleProtocol <= 120) {
                    handleMcpePacketSafely(content);
                    return;
                }
            }

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
            content.readerIndex(content.readerIndex() - 1);
            handleMcpePacketSafely(content);
        }
    }

    private void handleMcpePacketSafely(ByteBuf buf) {
        if (!buf.isReadable()) {
            return;
        }
        int packetId = buf.readUnsignedByte();
        if (packetId >= 0x80) {
            buf.readerIndex(buf.readerIndex() - 1);
            packetId = (int) (McpeBinary.readUnsignedVarInt(buf) & 0x3FF);
        }
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
                case McpeProtocol.REMOVE_BLOCK_PACKET:
                    handleMcpeRemoveBlock(buf);
                    break;
                case McpeProtocol.PLAYER_ACTION_PACKET:
                    handleMcpePlayerAction(buf);
                    break;
                case McpeProtocol.USE_ITEM_PACKET:
                    handleMcpeUseItem(buf);
                    break;
                case McpeProtocol.MOB_EQUIPMENT_PACKET:
                    handleMcpeMobEquipment(buf);
                    break;
                case McpeProtocol.DROP_ITEM_PACKET:
                    handleMcpeDropItem();
                    break;
                case McpeProtocol.INTERACT_PACKET:
                    handleMcpeInteract(buf);
                    break;
                case McpeProtocol.ANIMATE_PACKET:
                    handleMcpeAnimate(buf);
                    break;
                case McpeProtocol.RESPAWN_PACKET:
                    handleMcpeRespawn();
                    break;
                case McpeProtocol.ADD_HANGING_ENTITY_PACKET:
                    if (config.isDebugPackets()) {
                        logger.info("[MiniSMPBridge] Ignored 3DS AddHangingEntityPacket (0x10) from " + username);
                    }
                    break;
                default:
                    if (config.isDebugPackets()) {
                        logger.info("[MiniSMPBridge] Ignored unhandled 3DS packet 0x" + Integer.toHexString(packetId));
                    }
                    break;
            }
        } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
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

        sendPlayStatus(McpeProtocol.PLAY_STATUS_LOGIN_SUCCESS);

        ByteBuf rpInfo = Unpooled.buffer(8);
        rpInfo.writeByte(McpeProtocol.RESOURCE_PACKS_INFO_PACKET);
        rpInfo.writeBoolean(false);
        rpInfo.writeShortLE(0);
        rpInfo.writeShortLE(0);
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
        McpeBinary.readVarLong(buf);
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
            McpeBinary.readString(buf);
        }
        String message = McpeBinary.readString(buf);
        if (javaState == JavaState.PLAY && javaChannel != null && javaChannel.isActive() && !message.isEmpty()) {
            ByteBuf pkt = Unpooled.buffer(message.length() + 8);
            JavaBinary.writeVarInt(pkt, 0x02); // Serverbound ChatMessage (1.12.2)
            JavaBinary.writeString(pkt, message.length() > 256 ? message.substring(0, 256) : message);
            javaChannel.writeAndFlush(pkt);
        }
    }

    private void handleMcpeRemoveBlock(ByteBuf buf) {
        int[] coords = McpeBinary.readBlockCoords(buf);
        if (javaState != JavaState.PLAY || javaChannel == null || !javaChannel.isActive()) {
            return;
        }
        boolean matchedStart = (coords[0] == lastBreakX && coords[1] == lastBreakY && coords[2] == lastBreakZ);
        int face = matchedStart ? lastBreakFace : 1;
        if (!matchedStart) {
            sendJavaDigging(0, coords[0], coords[1], coords[2], face);
        }
        sendJavaDigging(2, coords[0], coords[1], coords[2], face);
        if (config.isDebugPackets()) {
            logger.info("[MiniSMPBridge] RemoveBlock (" + coords[0] + "," + coords[1] + "," + coords[2]
                    + ") matchedStart=" + matchedStart + " face=" + face);
        }
        lastBreakX = Integer.MIN_VALUE;
    }

    private void sendJavaDigging(int status, int x, int y, int z, int face) {
        ByteBuf pkt = Unpooled.buffer(16);
        JavaBinary.writeVarInt(pkt, 0x14); // Serverbound PlayerDigging (1.12.2)
        JavaBinary.writeVarInt(pkt, status);
        JavaBinary.writePosition(pkt, x, y, z);
        pkt.writeByte(face & 0xFF);
        javaChannel.writeAndFlush(pkt);
    }

    private void handleMcpePlayerAction(ByteBuf buf) {
        McpeBinary.readVarLong(buf);
        int action = McpeBinary.readVarInt(buf);
        int[] coords = McpeBinary.readBlockCoords(buf);
        int face = McpeBinary.readVarInt(buf);

        if (javaState != JavaState.PLAY || javaChannel == null || !javaChannel.isActive()) {
            return;
        }
        if (action == 0) { // ACTION_START_BREAK
            lastBreakX = coords[0];
            lastBreakY = coords[1];
            lastBreakZ = coords[2];
            lastBreakFace = (face >= 0 && face <= 5) ? face : 1;
            sendJavaDigging(0, coords[0], coords[1], coords[2], lastBreakFace);
        } else if (action == 1) { // ACTION_ABORT_BREAK
            if (lastBreakX != Integer.MIN_VALUE) {
                sendJavaDigging(1, lastBreakX, lastBreakY, lastBreakZ, lastBreakFace);
                lastBreakX = Integer.MIN_VALUE;
            }
        } else if (action == 2) { // ACTION_STOP_BREAK (sent with 0,0,0 alongside RemoveBlockPacket)
            // Intentionally ignored so RemoveBlockPacket finishes the break cleanly.
        } else if (action >= 9 && action <= 12) {
            int javaAction = action == 9 ? 3 : (action == 10 ? 4 : (action == 11 ? 0 : 1));
            ByteBuf pkt = Unpooled.buffer(12);
            JavaBinary.writeVarInt(pkt, 0x15); // EntityAction
            JavaBinary.writeVarInt(pkt, entityId);
            JavaBinary.writeVarInt(pkt, javaAction);
            JavaBinary.writeVarInt(pkt, 0);
            javaChannel.writeAndFlush(pkt);
        }
    }

    private void handleMcpeUseItem(ByteBuf buf) {
        int[] coords = McpeBinary.readBlockCoords(buf);
        McpeBinary.readUnsignedVarInt(buf); // interactBlockId
        int face = McpeBinary.readVarInt(buf);
        float[] faceVec = McpeBinary.readVector3f(buf);

        if (javaState != JavaState.PLAY || javaChannel == null || !javaChannel.isActive()) {
            return;
        }
        if (face >= 0 && face <= 5) {
            ByteBuf pkt = Unpooled.buffer(28);
            JavaBinary.writeVarInt(pkt, 0x1F); // Serverbound PlayerBlockPlacement (1.12.2)
            JavaBinary.writePosition(pkt, coords[0], coords[1], coords[2]);
            JavaBinary.writeVarInt(pkt, face);
            JavaBinary.writeVarInt(pkt, 0); // Main hand
            pkt.writeFloat(Math.max(0f, Math.min(1f, faceVec[0])));
            pkt.writeFloat(Math.max(0f, Math.min(1f, faceVec[1])));
            pkt.writeFloat(Math.max(0f, Math.min(1f, faceVec[2])));
            javaChannel.writeAndFlush(pkt);
        } else {
            ByteBuf pkt = Unpooled.buffer(4);
            JavaBinary.writeVarInt(pkt, 0x20); // Serverbound UseItem (1.12.2)
            JavaBinary.writeVarInt(pkt, 0);    // Main hand
            javaChannel.writeAndFlush(pkt);
        }
    }

    private void handleMcpeMobEquipment(ByteBuf buf) {
        McpeBinary.readVarLong(buf); // entityRuntimeId
        McpeBinary.skipSlot(buf);    // held item
        buf.readByte();              // inventorySlot
        int selected = buf.readUnsignedByte();
        if (selected >= 0 && selected <= 8) {
            this.selectedHotbarSlot = selected;
            if (javaState == JavaState.PLAY && javaChannel != null && javaChannel.isActive()) {
                ByteBuf pkt = Unpooled.buffer(4);
                JavaBinary.writeVarInt(pkt, 0x1A); // Serverbound HeldItemChange (1.12.2)
                pkt.writeShort(selected);
                javaChannel.writeAndFlush(pkt);
            }
        }
    }

    private void handleMcpeDropItem() {
        if (javaState == JavaState.PLAY && javaChannel != null && javaChannel.isActive()) {
            sendJavaDigging(4, 0, 0, 0, 0); // Drop single item
        }
    }

    private void handleMcpeInteract(ByteBuf buf) {
        int action = buf.readUnsignedByte();
        long target = McpeBinary.readVarLong(buf);
        if (javaState != JavaState.PLAY || javaChannel == null || !javaChannel.isActive()) {
            return;
        }
        if (action == 1 || action == 2) {
            ByteBuf pkt = Unpooled.buffer(12);
            JavaBinary.writeVarInt(pkt, 0x0A); // Serverbound UseEntity (1.12.2)
            JavaBinary.writeVarInt(pkt, (int) target);
            JavaBinary.writeVarInt(pkt, action == 1 ? 1 : 0);
            if (action == 2) {
                JavaBinary.writeVarInt(pkt, 0);
            }
            javaChannel.writeAndFlush(pkt);
        }
    }

    private void handleMcpeAnimate(ByteBuf buf) {
        int action = McpeBinary.readVarInt(buf);
        if (action == 1 && javaState == JavaState.PLAY && javaChannel != null && javaChannel.isActive()) {
            ByteBuf pkt = Unpooled.buffer(4);
            JavaBinary.writeVarInt(pkt, 0x1D); // Serverbound Animation (1.12.2)
            JavaBinary.writeVarInt(pkt, 0);
            javaChannel.writeAndFlush(pkt);
        }
    }

    private void handleMcpeRespawn() {
        if (javaState == JavaState.PLAY && javaChannel != null && javaChannel.isActive()) {
            ByteBuf pkt = Unpooled.buffer(4);
            JavaBinary.writeVarInt(pkt, 0x03); // Serverbound ClientStatus (1.12.2)
            JavaBinary.writeVarInt(pkt, 0);
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
                sendDisconnect("\u00a7cFailed to connect to Paper server.");
                close();
                return;
            }
            javaChannel = future.channel();
            sendJavaHandshakeAndLogin();
        });
    }

    private void sendJavaHandshakeAndLogin() {
        ByteBuf handshake = Unpooled.buffer(32);
        JavaBinary.writeVarInt(handshake, 0x00);
        JavaBinary.writeVarInt(handshake, 340);
        JavaBinary.writeString(handshake, config.getTargetHost());
        handshake.writeShort(config.getTargetPort());
        JavaBinary.writeVarInt(handshake, 2);
        javaChannel.write(handshake);

        ByteBuf loginStart = Unpooled.buffer(32);
        JavaBinary.writeVarInt(loginStart, 0x00);
        JavaBinary.writeString(loginStart, username);
        javaChannel.writeAndFlush(loginStart);
        javaState = JavaState.LOGIN;
    }

    private void sendJavaClientSettings() {
        if (javaChannel == null || !javaChannel.isActive()) {
            return;
        }
        ByteBuf pkt = Unpooled.buffer(24);
        JavaBinary.writeVarInt(pkt, 0x04); // Serverbound ClientSettings (1.12.2)
        JavaBinary.writeString(pkt, "en_US");
        pkt.writeByte(config.getMaxViewDistance());
        JavaBinary.writeVarInt(pkt, 0);
        pkt.writeBoolean(true);
        pkt.writeByte(0x7F);
        JavaBinary.writeVarInt(pkt, 1);
        javaChannel.writeAndFlush(pkt);
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
            JavaBinary.readString(msg);
            JavaBinary.readString(msg);
            javaState = JavaState.PLAY;
        } else if (packetId == 0x00) {
            String reason = JavaBinary.jsonChatToLegacy(JavaBinary.readString(msg));
            sendDisconnect(reason);
            close();
        }
    }

    private void handleJavaPlayPacket(int packetId, ByteBuf msg) {
        switch (packetId) {
            case 0x00: { // SpawnObject (Clientbound 1.12.2) - renders dropped items!
                if (entities.size() >= MAX_TRACKED_ENTITIES) {
                    break;
                }
                int eid = JavaBinary.readVarInt(msg);
                JavaBinary.readUUID(msg);
                int type = msg.readUnsignedByte();
                double x = msg.readDouble();
                double y = msg.readDouble();
                double z = msg.readDouble();
                msg.readByte(); // pitch
                msg.readByte(); // yaw
                msg.readInt();  // data
                float vx = msg.readShort() / 8000.0f;
                float vy = msg.readShort() / 8000.0f;
                float vz = msg.readShort() / 8000.0f;

                if (type == 2) { // Dropped Item Stack
                    TrackedEntity e = new TrackedEntity();
                    e.x = (float) x;
                    e.y = (float) y;
                    e.z = (float) z;
                    e.vx = vx;
                    e.vy = vy;
                    e.vz = vz;
                    e.isPendingItem = true;
                    entities.put(eid, e);
                }
                break;
            }
            case 0x03: { // SpawnMob (Clientbound 1.12.2)
                if (entities.size() >= MAX_TRACKED_ENTITIES) {
                    break;
                }
                int eid = JavaBinary.readVarInt(msg);
                JavaBinary.readUUID(msg);
                int javaType = JavaBinary.readVarInt(msg);
                double x = msg.readDouble();
                double y = msg.readDouble();
                double z = msg.readDouble();
                float yRot = (msg.readByte() & 0xFF) * (360.0f / 256.0f);
                float xRot = (msg.readByte() & 0xFF) * (360.0f / 256.0f);
                msg.readByte(); // headPitch
                float vx = msg.readShort() / 8000.0f;
                float vy = msg.readShort() / 8000.0f;
                float vz = msg.readShort() / 8000.0f;

                int mcpeType = mapJavaMobTypeToMcpe(javaType);
                if (mcpeType > 0) {
                    TrackedEntity e = new TrackedEntity();
                    e.x = (float) x;
                    e.y = (float) y;
                    e.z = (float) z;
                    e.yaw = yRot;
                    e.pitch = xRot;
                    e.spawnedOnMcpe = true;
                    entities.put(eid, e);
                    sendMcpeAddEntity(eid, mcpeType, e.x, e.y, e.z, vx, vy, vz, yRot, xRot);
                }
                break;
            }
            case 0x05: { // SpawnPlayer (Clientbound 1.12.2)
                if (entities.size() >= MAX_TRACKED_ENTITIES) {
                    break;
                }
                int eid = JavaBinary.readVarInt(msg);
                UUID playerUuid = JavaBinary.readUUID(msg);
                double x = msg.readDouble();
                double y = msg.readDouble();
                double z = msg.readDouble();
                float yRot = (msg.readByte() & 0xFF) * (360.0f / 256.0f);
                float xRot = (msg.readByte() & 0xFF) * (360.0f / 256.0f);

                TrackedEntity e = new TrackedEntity();
                e.x = (float) x;
                e.y = (float) y;
                e.z = (float) z;
                e.yaw = yRot;
                e.pitch = xRot;
                e.spawnedOnMcpe = true;
                entities.put(eid, e);
                sendMcpeAddPlayer(eid, playerUuid, "Player", e.x, e.y, e.z, yRot, xRot);
                break;
            }
            case 0x3C: { // EntityMetadata (Clientbound 1.12.2) - completes dropped item spawn!
                int eid = JavaBinary.readVarInt(msg);
                TrackedEntity e = entities.get(eid);
                if (e != null && e.isPendingItem && !e.spawnedOnMcpe) {
                    int[] item = extractDroppedItemFromMetadata(msg);
                    if (item != null && item[0] > 0) {
                        e.isPendingItem = false;
                        e.spawnedOnMcpe = true;
                        sendMcpeAddItemEntity(eid, translateItem(item), e.x, e.y, e.z, e.vx, e.vy, e.vz);
                    }
                }
                break;
            }
            case 0x26: { // EntityRelativeMove (Clientbound 1.12.2)
                int eid = JavaBinary.readVarInt(msg);
                TrackedEntity e = entities.get(eid);
                if (e != null && e.spawnedOnMcpe) {
                    e.x += msg.readShort() / 4096.0f;
                    e.y += msg.readShort() / 4096.0f;
                    e.z += msg.readShort() / 4096.0f;
                    boolean onGround = msg.readBoolean();
                    sendMcpeMoveEntity(eid, e.x, e.y, e.z, e.yaw, e.pitch, onGround);
                }
                break;
            }
            case 0x27: { // EntityLookAndRelativeMove (Clientbound 1.12.2)
                int eid = JavaBinary.readVarInt(msg);
                TrackedEntity e = entities.get(eid);
                if (e != null && e.spawnedOnMcpe) {
                    e.x += msg.readShort() / 4096.0f;
                    e.y += msg.readShort() / 4096.0f;
                    e.z += msg.readShort() / 4096.0f;
                    e.yaw = (msg.readByte() & 0xFF) * (360.0f / 256.0f);
                    e.pitch = (msg.readByte() & 0xFF) * (360.0f / 256.0f);
                    boolean onGround = msg.readBoolean();
                    sendMcpeMoveEntity(eid, e.x, e.y, e.z, e.yaw, e.pitch, onGround);
                }
                break;
            }
            case 0x4C: { // EntityTeleport (Clientbound 1.12.2)
                int eid = JavaBinary.readVarInt(msg);
                TrackedEntity e = entities.get(eid);
                if (e != null && e.spawnedOnMcpe) {
                    e.x = (float) msg.readDouble();
                    e.y = (float) msg.readDouble();
                    e.z = (float) msg.readDouble();
                    e.yaw = (msg.readByte() & 0xFF) * (360.0f / 256.0f);
                    e.pitch = (msg.readByte() & 0xFF) * (360.0f / 256.0f);
                    boolean onGround = msg.readBoolean();
                    sendMcpeMoveEntity(eid, e.x, e.y, e.z, e.yaw, e.pitch, onGround);
                }
                break;
            }
            case 0x4B: { // CollectItem (Clientbound 1.12.2)
                int collected = JavaBinary.readVarInt(msg);
                int collector = JavaBinary.readVarInt(msg);
                TrackedEntity removed = entities.remove(collected);
                if (removed != null && removed.spawnedOnMcpe) {
                    ByteBuf out = Unpooled.buffer(16);
                    out.writeByte(McpeProtocol.TAKE_ITEM_ENTITY_PACKET);
                    McpeBinary.writeVarLong(out, collector);
                    McpeBinary.writeVarLong(out, collected);
                    sendMcpePacket(out, false);
                }
                break;
            }
            case 0x32: { // DestroyEntities (Clientbound 1.12.2)
                int count = JavaBinary.readVarInt(msg);
                for (int i = 0; i < count && msg.isReadable(); i++) {
                    int eid = JavaBinary.readVarInt(msg);
                    TrackedEntity removed = entities.remove(eid);
                    if (removed != null && removed.spawnedOnMcpe) {
                        ByteBuf out = Unpooled.buffer(12);
                        out.writeByte(McpeProtocol.REMOVE_ENTITY_PACKET);
                        McpeBinary.writeVarLong(out, eid);
                        sendMcpePacket(out, false);
                    }
                }
                break;
            }
            case 0x1F: { // KeepAlive (Clientbound 1.12.2)
                long id = msg.readLong();
                ByteBuf reply = Unpooled.buffer(12);
                JavaBinary.writeVarInt(reply, 0x0B);
                reply.writeLong(id);
                javaChannel.writeAndFlush(reply);
                break;
            }
            case 0x23: { // JoinGame
                this.entityId = msg.readInt();
                this.gamemode = msg.readUnsignedByte() & 0x0F;
                this.dimension = msg.readInt();
                int difficulty = msg.readUnsignedByte();
                sendJavaClientSettings();
                sendMcpeStartGame(difficulty);
                break;
            }
            case 0x2C: { // PlayerAbilities (Clientbound 1.12.2)
                if (msg.readableBytes() >= 9) {
                    byte flags = msg.readByte();
                    msg.readFloat();
                    float walkSpeed = msg.readFloat();
                    this.isFlying = (flags & 0x02) != 0;
                    this.allowFlight = (flags & 0x04) != 0;
                    this.currentMoveSpeed = (walkSpeed > 0.0f && walkSpeed <= 1.0f) ? walkSpeed : 0.10f;
                    sendMcpeAttributes();
                    sendMcpeAdventureSettings();
                }
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

                ByteBuf confirm = Unpooled.buffer(8);
                JavaBinary.writeVarInt(confirm, 0x00);
                JavaBinary.writeVarInt(confirm, teleportId);
                javaChannel.writeAndFlush(confirm);

                if (!spawned) {
                    spawned = true;
                    sendMcpeAttributes();
                    sendMcpeAdventureSettings();
                    sendPlayStatus(McpeProtocol.PLAY_STATUS_PLAYER_SPAWN);
                    sendMcpeFullInventory();
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
            case 0x14: { // WindowItems (Clientbound 1.12.2)
                int windowId = msg.readUnsignedByte();
                int count = msg.readShort() & 0xFFFF;
                if (windowId == 0) {
                    try {
                        for (int i = 0; i < count && msg.isReadable(); i++) {
                            int[] slot = JavaBinary.readSlot(msg);
                            int mcpeSlot = javaSlotToMcpeSlot(i);
                            if (mcpeSlot >= 0) {
                                inventory[mcpeSlot] = translateItem(slot);
                            }
                        }
                        if (spawned) {
                            sendMcpeFullInventory();
                        }
                    } catch (Exception ignored) {
                    }
                }
                break;
            }
            case 0x16: { // SetSlot (Clientbound 1.12.2)
                int windowId = msg.readByte();
                int javaSlot = msg.readShort();
                if (windowId == 0 || windowId == -2) {
                    try {
                        int[] item = translateItem(JavaBinary.readSlot(msg));
                        int mcpeSlot = (windowId == -2)
                                ? (javaSlot >= 0 && javaSlot < 36 ? javaSlot : -1)
                                : javaSlotToMcpeSlot(javaSlot);
                        if (mcpeSlot >= 0) {
                            inventory[mcpeSlot] = item;
                            if (spawned) {
                                sendMcpeSetSlot(mcpeSlot, item);
                                sendMcpeFullInventory();
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                break;
            }
            case 0x3A: { // HeldItemChange (Clientbound 1.12.2)
                if (msg.isReadable()) {
                    int slot = msg.readByte() & 0xFF;
                    if (slot <= 8) {
                        this.selectedHotbarSlot = slot;
                    }
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
                int food = msg.readableBytes() >= 1 ? JavaBinary.readVarInt(msg) : 20;
                this.currentHealth = Math.max(0.0f, Math.min(20.0f, health));
                this.currentHunger = Math.max(0.0f, Math.min(20.0f, (float) food));
                ByteBuf out = Unpooled.buffer(8);
                out.writeByte(McpeProtocol.SET_HEALTH_PACKET);
                McpeBinary.writeUnsignedVarInt(out, Math.max(0, Math.round(this.currentHealth)));
                sendMcpePacket(out, false);
                sendMcpeAttributes();
                break;
            }
            case 0x35: { // Respawn
                this.dimension = msg.readInt();
                entities.clear();
                ByteBuf out = Unpooled.buffer(16);
                out.writeByte(McpeProtocol.RESPAWN_PACKET);
                McpeBinary.writeVector3f(out, posX, posY + EYE_HEIGHT, posZ);
                sendMcpePacket(out, true);
                sendMcpeAttributes();
                sendMcpeAdventureSettings();
                break;
            }
            case 0x47: { // TimeUpdate
                msg.readLong();
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

    private static int[] extractDroppedItemFromMetadata(ByteBuf msg) {
        try {
            while (msg.isReadable()) {
                int index = msg.readUnsignedByte();
                if (index == 0xFF) {
                    break;
                }
                int type = JavaBinary.readVarInt(msg);
                switch (type) {
                    case 0: msg.readByte(); break;
                    case 1: JavaBinary.readVarInt(msg); break;
                    case 2: msg.readFloat(); break;
                    case 3: JavaBinary.readString(msg); break;
                    case 4: JavaBinary.readString(msg); break;
                    case 5: {
                        int[] slot = JavaBinary.readSlot(msg);
                        if (index == 6) {
                            return slot;
                        }
                        break;
                    }
                    case 6: msg.readBoolean(); break;
                    case 7: msg.skipBytes(12); break;
                    case 8: msg.readLong(); break;
                    case 9: if (msg.readBoolean()) msg.readLong(); break;
                    case 10: JavaBinary.readVarInt(msg); break;
                    case 11: if (msg.readBoolean()) msg.skipBytes(16); break;
                    case 12: JavaBinary.readVarInt(msg); break;
                    default: return null;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int mapJavaMobTypeToMcpe(int javaType) {
        switch (javaType) {
            case 50: return 33; // Creeper
            case 51: return 34; // Skeleton
            case 52: return 35; // Spider
            case 54: return 32; // Zombie
            case 55: return 37; // Slime
            case 56: return 41; // Ghast
            case 57: return 36; // Zombie Pigman
            case 58: return 38; // Enderman
            case 59: return 40; // Cave Spider
            case 60: return 39; // Silverfish
            case 61: return 43; // Blaze
            case 62: return 42; // Magma Cube
            case 65: return 19; // Bat
            case 66: return 45; // Witch
            case 90: return 12; // Pig
            case 91: return 13; // Sheep
            case 92: return 11; // Cow
            case 93: return 10; // Chicken
            case 94: return 17; // Squid
            case 95: return 14; // Wolf
            case 96: return 16; // Mooshroom
            case 98: return 22; // Ocelot
            case 99: return 20; // Iron Golem
            case 100: return 23; // Horse
            case 101: return 18; // Rabbit
            case 120: return 15; // Villager
            default: return 0;
        }
    }

    private void sendMcpeAddItemEntity(int eid, int[] item, float x, float y, float z, float vx, float vy, float vz) {
        ByteBuf out = Unpooled.buffer(48);
        out.writeByte(McpeProtocol.ADD_ITEM_ENTITY_PACKET);
        McpeBinary.writeVarLong(out, eid);
        McpeBinary.writeVarLong(out, eid);
        McpeBinary.writeSlot(out, item[0], item[1], item[2]);
        McpeBinary.writeVector3f(out, x, y, z);
        McpeBinary.writeVector3f(out, vx, vy, vz);
        sendMcpePacket(out, false);
    }

    private void sendMcpeAddEntity(int eid, int type, float x, float y, float z, float vx, float vy, float vz, float yaw, float pitch) {
        ByteBuf out = Unpooled.buffer(64);
        out.writeByte(McpeProtocol.ADD_ENTITY_PACKET);
        McpeBinary.writeVarLong(out, eid);
        McpeBinary.writeVarLong(out, eid);
        McpeBinary.writeUnsignedVarInt(out, type);
        McpeBinary.writeVector3f(out, x, y, z);
        McpeBinary.writeVector3f(out, vx, vy, vz);
        out.writeFloatLE(pitch * (256.0f / 360.0f));
        out.writeFloatLE(yaw * (256.0f / 360.0f));
        McpeBinary.writeUnsignedVarInt(out, 0); // attributes
        McpeBinary.writeUnsignedVarInt(out, 0); // metadata
        McpeBinary.writeUnsignedVarInt(out, 0); // links
        sendMcpePacket(out, false);
    }

    private void sendMcpeAddPlayer(int eid, UUID playerUuid, String name, float x, float y, float z, float yaw, float pitch) {
        ByteBuf out = Unpooled.buffer(96);
        out.writeByte(McpeProtocol.ADD_PLAYER_PACKET);
        McpeBinary.writeUUID(out, playerUuid);
        McpeBinary.writeString(out, name);
        McpeBinary.writeVarLong(out, eid);
        McpeBinary.writeVarLong(out, eid);
        McpeBinary.writeVector3f(out, x, y, z);
        McpeBinary.writeVector3f(out, 0f, 0f, 0f);
        out.writeFloatLE(pitch);
        out.writeFloatLE(yaw);
        out.writeFloatLE(yaw);
        McpeBinary.writeSlot(out, 0, 0, 0);
        McpeBinary.writeUnsignedVarInt(out, 0); // metadata
        sendMcpePacket(out, false);
    }

    private void sendMcpeMoveEntity(int eid, float x, float y, float z, float yaw, float pitch, boolean onGround) {
        ByteBuf out = Unpooled.buffer(32);
        out.writeByte(McpeProtocol.MOVE_ENTITY_PACKET);
        McpeBinary.writeVarLong(out, eid);
        McpeBinary.writeVector3f(out, x, y, z);
        out.writeByte((byte) (pitch / (360.0 / 256.0)));
        out.writeByte((byte) (yaw / (360.0 / 256.0)));
        out.writeByte((byte) (yaw / (360.0 / 256.0)));
        out.writeByte(onGround ? 1 : 0);
        sendMcpePacket(out, false);
    }

    private static int javaSlotToMcpeSlot(int javaSlot) {
        if (javaSlot >= 36 && javaSlot <= 44) {
            return javaSlot - 36; // Hotbar 0..8
        }
        if (javaSlot >= 9 && javaSlot <= 35) {
            return javaSlot; // Main inventory 9..35
        }
        return -1;
    }

    private static int[] translateItem(int[] javaSlot) {
        if (javaSlot[0] <= 0 || javaSlot[1] <= 0) {
            return new int[]{0, 0, 0};
        }
        int id = javaSlot[0] < 256 ? BlockTranslator.translateBlockId(javaSlot[0]) : javaSlot[0];
        return new int[]{id, javaSlot[1], javaSlot[2]};
    }

    private void sendMcpeFullInventory() {
        ByteBuf out = Unpooled.buffer(256);
        out.writeByte(McpeProtocol.CONTAINER_SET_CONTENT_PACKET);
        McpeBinary.writeUnsignedVarInt(out, 0); // SPECIAL_INVENTORY
        McpeBinary.writeVarLong(out, entityId);
        McpeBinary.writeUnsignedVarInt(out, 45); // 36 real + 9 dummy air slots (matching Nukkit-Retro)
        for (int i = 0; i < 36; i++) {
            McpeBinary.writeSlot(out, inventory[i][0], inventory[i][1], inventory[i][2]);
        }
        for (int i = 0; i < 9; i++) {
            McpeBinary.writeSlot(out, 0, 0, 0);
        }
        McpeBinary.writeUnsignedVarInt(out, 9); // Hotbar links
        for (int i = 0; i < 9; i++) {
            McpeBinary.writeVarInt(out, i + 9);
        }
        sendMcpePacket(out, false);
    }

    private void sendMcpeSetSlot(int mcpeSlot, int[] item) {
        ByteBuf out = Unpooled.buffer(32);
        out.writeByte(McpeProtocol.CONTAINER_SET_SLOT_PACKET);
        out.writeByte(0); // window 0
        McpeBinary.writeVarInt(out, mcpeSlot);
        McpeBinary.writeVarInt(out, 0); // hotbarSlot = 0 (matching Nukkit-Retro PlayerInventory.sendSlot)
        McpeBinary.writeSlot(out, item[0], item[1], item[2]);
        out.writeByte(selectedHotbarSlot);
        sendMcpePacket(out, false);
    }

    private void sendMcpeAdventureSettings() {
        int flags = 0;
        flags |= (1 << 5); // autoJump
        if (allowFlight) {
            flags |= (1 << 6);
        }
        if (isFlying) {
            flags |= (1 << 9);
        }
        ByteBuf out = Unpooled.buffer(12);
        out.writeByte(McpeProtocol.ADVENTURE_SETTINGS_PACKET);
        McpeBinary.writeUnsignedVarInt(out, flags);
        McpeBinary.writeUnsignedVarInt(out, 1);
        sendMcpePacket(out, false);
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
        McpeBinary.writeVarInt(out, 0);
        McpeBinary.writeVarInt(out, dimension == -1 ? 1 : (dimension == 1 ? 2 : 0));
        McpeBinary.writeVarInt(out, 1);
        McpeBinary.writeVarInt(out, gamemode);
        McpeBinary.writeVarInt(out, difficulty);
        McpeBinary.writeBlockCoords(out, (int) posX, (int) posY, (int) posZ);
        out.writeBoolean(true);
        McpeBinary.writeVarInt(out, -1);
        out.writeBoolean(false);
        out.writeFloatLE(0.0f);
        out.writeFloatLE(0.0f);
        out.writeBoolean(true);
        out.writeBoolean(false);
        McpeBinary.writeUnsignedVarInt(out, 0);
        McpeBinary.writeString(out, "");
        McpeBinary.writeString(out, "MiniSMP");
        McpeBinary.writeString(out, "");
        sendMcpePacket(out, true);

        ByteBuf radius = Unpooled.buffer(8);
        radius.writeByte(McpeProtocol.CHUNK_RADIUS_UPDATED_PACKET);
        McpeBinary.writeVarInt(radius, config.getMaxViewDistance());
        sendMcpePacket(radius, false);

        sendMcpeAttributes();
        sendMcpeAdventureSettings();
    }

    private void sendMcpeAttributes() {
        ByteBuf out = Unpooled.buffer(160);
        out.writeByte(McpeProtocol.UPDATE_ATTRIBUTES_PACKET);
        McpeBinary.writeVarLong(out, entityId);
        McpeBinary.writeUnsignedVarInt(out, 4);
        writeAttribute(out, 0.0f, Float.MAX_VALUE, currentMoveSpeed, 0.10f, "minecraft:movement");
        writeAttribute(out, 0.0f, 20.0f, currentHealth, 20.0f, "minecraft:health");
        writeAttribute(out, 0.0f, 20.0f, currentHunger, 20.0f, "minecraft:player.hunger");
        writeAttribute(out, 0.0f, 20.0f, 5.0f, 5.0f, "minecraft:player.saturation");
        sendMcpePacket(out, false);
    }

    private static void writeAttribute(ByteBuf out, float min, float max, float val, float def, String name) {
        out.writeFloatLE(min);
        out.writeFloatLE(max);
        out.writeFloatLE(val);
        out.writeFloatLE(def);
        McpeBinary.writeString(out, name);
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
        entities.clear();
        if (javaChannel != null && javaChannel.isOpen()) {
            javaChannel.close();
        }
        if (rakChannel.isOpen()) {
            rakChannel.close();
        }
        onClosed.run();
    }
}
