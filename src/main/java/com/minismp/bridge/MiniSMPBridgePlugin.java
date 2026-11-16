package com.minismp.bridge;

import com.minismp.bridge.config.BridgeConfig;
import com.minismp.bridge.mcpe.RakNetBridgeServer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class MiniSMPBridgePlugin extends JavaPlugin {

    private BridgeConfig bridgeConfig;
    private RakNetBridgeServer rakServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadBridge();

        // Periodically refresh RakNet MOTD player count on an async thread (lightweight)
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (rakServer != null) {
                rakServer.updateAdvertisement(Bukkit.getOnlinePlayers().size());
            }
        }, 100L, 100L);
    }

    @Override
    public void onDisable() {
        if (rakServer != null) {
            rakServer.shutdown();
            rakServer = null;
        }
        getLogger().info("[MiniSMPBridge] Stopped RakNet UDP bridge.");
    }

    private void reloadBridge() {
        if (rakServer != null) {
            rakServer.shutdown();
        }
        reloadConfig();
        this.bridgeConfig = new BridgeConfig(getConfig());
        this.rakServer = new RakNetBridgeServer(bridgeConfig, getLogger());
        this.rakServer.start(Bukkit.getOnlinePlayers().size());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("minismpbridge")) {
            return false;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadBridge();
            sender.sendMessage("\u00a7a[MiniSMPBridge] Reloaded config and restarted RakNet UDP listener.");
            return true;
        }
        int active = rakServer != null ? rakServer.getActiveSessionCount() : 0;
        sender.sendMessage("\u00a7b[MiniSMPBridge] \u00a77UDP Port: \u00a7f" + bridgeConfig.getUdpPort()
                + " \u00a77-> Java: \u00a7f" + bridgeConfig.getTargetHost() + ":" + bridgeConfig.getTargetPort()
                + " \u00a77| 3DS Sessions: \u00a7a" + active);
        return true;
    }
}
