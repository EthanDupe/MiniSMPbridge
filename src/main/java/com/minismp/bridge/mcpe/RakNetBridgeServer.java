package com.minismp.bridge.mcpe;

import com.minismp.bridge.config.BridgeConfig;
import com.minismp.bridge.session.BridgeSession;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.RakServerChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.config.RakServerCookieMode;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.handler.codec.raknet.common.EncapsulatedToMessageHandler;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Embedded non-blocking Cloudburst RakNet UDP server listening on the shared FalixNodes port (25318).
 */
public final class RakNetBridgeServer {

    private final BridgeConfig config;
    private final Logger logger;
    private final long serverGuid = UUID.randomUUID().getLeastSignificantBits();

    private final EventLoopGroup rakGroup = new NioEventLoopGroup(2);
    private final EventLoopGroup tcpGroup = new NioEventLoopGroup(2);
    private final Map<InetSocketAddress, BridgeSession> sessions = new ConcurrentHashMap<>();

    private RakServerChannel serverChannel;

    public RakNetBridgeServer(BridgeConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public void start(int initialOnlinePlayers) {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(rakGroup)
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_GUID, serverGuid)
                .option(RakChannelOption.RAK_SUPPORTED_PROTOCOLS, new int[]{McpeProtocol.RAKNET_PROTOCOL_VERSION})
                .option(RakChannelOption.RAK_UNCONNECTED_MAGIC, Unpooled.wrappedBuffer(McpeProtocol.RAKNET_MAGIC))
                .option(RakChannelOption.RAK_SERVER_COOKIE_MODE, RakServerCookieMode.ACTIVE)
                .option(RakChannelOption.RAK_MAX_CONNECTIONS, config.getMaxPlayers())
                .option(RakChannelOption.RAK_ADVERTISEMENT, buildAdvertisement(initialOnlinePlayers))
                .childOption(RakChannelOption.RAK_ORDERING_CHANNELS, 16)
                .childHandler(new ChannelInitializer<RakChildChannel>() {
                    @Override
                    protected void initChannel(RakChildChannel ch) {
                        ch.pipeline().addLast(EncapsulatedToMessageHandler.NAME, EncapsulatedToMessageHandler.INSTANCE);
                        ch.pipeline().addLast(new RakSessionHandler());
                    }
                });

        InetSocketAddress bindAddr = new InetSocketAddress(config.getUdpBindIp(), config.getUdpPort());
        ChannelFuture future = bootstrap.bind(bindAddr).syncUninterruptibly();
        if (!future.isSuccess()) {
            shutdown();
            throw new IllegalStateException("Failed to bind RakNet UDP listener on " + bindAddr, future.cause());
        }
        this.serverChannel = (RakServerChannel) future.channel();
        logger.info("[MiniSMPBridge] RakNet UDP listener active on " + bindAddr
                + " -> bridging to " + config.getTargetHost() + ":" + config.getTargetPort());
    }

    public void updateAdvertisement(int onlinePlayers) {
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.config().setOption(RakChannelOption.RAK_ADVERTISEMENT, buildAdvertisement(onlinePlayers));
        }
    }

    private ByteBuf buildAdvertisement(int onlinePlayers) {
        String cleanMotd = config.getMotd().replace(";", "\\;");
        String adv = "MCPE;" + cleanMotd + ";"
                + McpeProtocol.PROTOCOL_VERSION + ";"
                + McpeProtocol.MINECRAFT_VERSION + ";"
                + onlinePlayers + ";"
                + config.getMaxPlayers() + ";"
                + serverGuid + ";MiniSMP;Survival";
        return Unpooled.wrappedBuffer(adv.getBytes(StandardCharsets.UTF_8));
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    public void shutdown() {
        for (BridgeSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close().syncUninterruptibly();
        }
        rakGroup.shutdownGracefully();
        tcpGroup.shutdownGracefully();
    }

    private final class RakSessionHandler extends SimpleChannelInboundHandler<RakMessage> {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            RakChildChannel ch = (RakChildChannel) ctx.channel();
            InetSocketAddress addr = ch.remoteAddress();
            BridgeSession session = new BridgeSession(ch, tcpGroup, config, logger, () -> sessions.remove(addr));
            sessions.put(addr, session);
            ctx.fireChannelActive();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, RakMessage msg) {
            RakChildChannel ch = (RakChildChannel) ctx.channel();
            BridgeSession session = sessions.get(ch.remoteAddress());
            if (session != null) {
                session.handleRakMessage(msg.content());
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            RakChildChannel ch = (RakChildChannel) ctx.channel();
            BridgeSession session = sessions.remove(ch.remoteAddress());
            if (session != null) {
                session.close();
            }
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (config.isDebugPackets()) {
                logger.warning("[MiniSMPBridge] RakNet channel error: " + cause.getMessage());
            }
            ctx.close();
        }
    }
}
