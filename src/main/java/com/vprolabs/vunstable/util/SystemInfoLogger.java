package com.vprolabs.vunstable.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * SystemInfoLogger - Generates a minimal system information log for debugging crashes.
 * 
 * v1.2.0: Streamlined to only essential debug/crash information.
 */
public class SystemInfoLogger {
    
    private final JavaPlugin plugin;
    private final File logFile;
    
    public SystemInfoLogger(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "system-info.log");
    }
    
    /**
     * Generate the system info log file (minimal, essential data only)
     */
    public void generate() {
        // Collect data on main thread, then write async
        final int onlinePlayers = Bukkit.getOnlinePlayers().size();
        final int maxPlayers = Bukkit.getMaxPlayers();
        final String[] worldNames = Bukkit.getWorlds().stream()
            .map(World::getName)
            .toArray(String[]::new);
        
        // vAPI does not expose async scheduling; use Bukkit directly for file I/O
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!plugin.getDataFolder().exists()) {
                    plugin.getDataFolder().mkdirs();
                }
                
                try (PrintWriter writer = new PrintWriter(new FileWriter(logFile))) {
                    writer.println("=== vUnstable Debug Log ===");
                    writer.println("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                    writer.println();
                    
                    // Plugin Info
                    writer.println("[PLUGIN]");
                    writer.println("Version: " + plugin.getPluginMeta().getVersion());
                    writer.println();
                    
                    // Server Info (critical for compatibility issues)
                    writer.println("[SERVER]");
                    writer.println("Type: " + Bukkit.getName());
                    writer.println("Version: " + Bukkit.getVersion());
                    writer.println("Minecraft: " + Bukkit.getMinecraftVersion());
                    writer.println("Bukkit API: " + Bukkit.getBukkitVersion());
                    writer.println();
                    
                    // Java Info (critical for method handle issues)
                    writer.println("[JAVA]");
                    writer.println("Version: " + System.getProperty("java.version"));
                    writer.println("Vendor: " + System.getProperty("java.vendor"));
                    writer.println();
                    
                    // Memory (critical for OOM debugging)
                    writer.println("[MEMORY MB]");
                    Runtime runtime = Runtime.getRuntime();
                    writer.println("Max: " + (runtime.maxMemory() / 1024 / 1024));
                    writer.println("Used: " + ((runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024));
                    writer.println("Free: " + (runtime.freeMemory() / 1024 / 1024));
                    writer.println();
                    
                    // TPS (critical for performance issues)
                    writer.println("[PERFORMANCE]");
                    try {
                        double[] tps = Bukkit.getServer().getTPS();
                        if (tps.length > 0) {
                            writer.println("TPS 1m: " + String.format("%.2f", tps[0]));
                        }
                    } catch (UnsupportedOperationException e) {
                        writer.println("TPS: N/A (Folia)");
                    }
                    writer.println("Players: " + onlinePlayers + "/" + maxPlayers);
                    writer.println();
                    
                    // Worlds (basic names only)
                    writer.println("[WORLDS]");
                    for (String name : worldNames) {
                        writer.println("  - " + name);
                    }
                    writer.println();
                    
                    // Recent JVM flags (first 10 only, for GC/config issues)
                    writer.println("[JVM FLAGS]");
                    RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
                    var jvmArgs = runtimeMXBean.getInputArguments();
                    int count = 0;
                    for (String arg : jvmArgs) {
                        if (count >= 10) {
                            writer.println("  ... (" + (jvmArgs.size() - 10) + " more)");
                            break;
                        }
                        writer.println("  " + arg);
                        count++;
                    }
                    if (jvmArgs.isEmpty()) {
                        writer.println("  (none)");
                    }
                    writer.println();
                    
                    writer.println("Support: discord.gg/SNzUYWbc5Q");
                }
                
                plugin.getLogger().info("[vUnstable] Debug log generated: " + logFile.getName());
                
            } catch (IOException e) {
                plugin.getLogger().warning("[vUnstable] Failed to generate debug log: " + e.getMessage());
            }
        });
    }
    
    /**
     * Get the log file
     */
    public File getLogFile() {
        return logFile;
    }
}
