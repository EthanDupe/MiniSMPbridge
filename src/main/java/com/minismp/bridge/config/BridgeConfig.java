package com.minismp.bridge.config;

import org.bukkit.configuration.file.FileConfiguration;

public final class BridgeConfig {
    private final String udpBindIp;
    private final int udpPort;
    private final String targetHost;
    private final int targetPort;
    private final String motd;
    private final int maxPlayers;
    private final int maxViewDistance;
    private final int compressionLevel;
    private final boolean debugPackets;

    public BridgeConfig(FileConfiguration cfg) {
        this.udpBindIp = cfg.getString("udp-bind-ip", "0.0.0.0");
        this.udpPort = cfg.getInt("udp-port", 25318);
        this.targetHost = cfg.getString("target-host", "127.0.0.1");
        this.targetPort = cfg.getInt("target-port", 25318);
        this.motd = cfg.getString("motd", "\u00a7l\u00a7bMiniSMP \u00a7r\u00a77- \u00a7aNow Available! \u00a76[v1.0]");
        this.maxPlayers = Math.max(1, cfg.getInt("max-players", 20));
        this.maxViewDistance = Math.max(2, Math.min(12, cfg.getInt("max-view-distance", 6)));
        this.compressionLevel = Math.max(1, Math.min(9, cfg.getInt("compression-level", 4)));
        this.debugPackets = cfg.getBoolean("debug-packets", false);
    }

    public String getUdpBindIp() {
        return udpBindIp;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public String getTargetHost() {
        return targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public String getMotd() {
        return motd;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public int getMaxViewDistance() {
        return maxViewDistance;
    }

    public int getCompressionLevel() {
        return compressionLevel;
    }

    public boolean isDebugPackets() {
        return debugPackets;
    }
}
