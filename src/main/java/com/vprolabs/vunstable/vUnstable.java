package com.vprolabs.vunstable;

import com.vprolabs.vunstable.command.RodCommand;
import com.vprolabs.vunstable.config.ConfigManager;
import com.vprolabs.vunstable.config.SpigotOptimizer;
import com.vprolabs.vunstable.engine.AsyncSpawnEngine;
import com.vprolabs.vunstable.listener.AdminErrorNotifier;
import com.vprolabs.vunstable.listener.AdminNotifier;
import com.vprolabs.vunstable.listener.EntityListener;
import com.vprolabs.vunstable.listener.PlayerListener;
import com.vprolabs.vunstable.listener.UpdateListener;
import com.vprolabs.vunstable.rod.RodManager;
import com.vprolabs.vunstable.util.ErrorHandler;
import com.vprolabs.vunstable.util.SystemInfoLogger;
import com.vprolabs.vunstable.util.UpdateChecker;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.vprolabs.vapi.VAPI;

/**
 * vUnstable v1.2.0 - The Ultimate Orbital Strike Cannon
 * 
 * Features:
 * - Async spawning engine
 * - ConfigManager singleton
 * - SpigotOptimizer for auto-tuning max-tnt-per-tick
 * - AdminNotifier for panel environment optimization alerts
 * - UpdateChecker/UpdateListener for automatic update notifications
 * - NMS MethodHandles for performance
 * - Ground detection for auto-detonation
 * - Configurable block drops
 * 
 * @author vProLabs
 * @version 1.2.0
 */
public class vUnstable extends JavaPlugin {
    
    private static vUnstable instance;
    private boolean vapiInstalled = false;
    private ConfigManager configManager;
    private AsyncSpawnEngine spawnEngine;
    private RodManager rodManager;
    private SpigotOptimizer optimizer;
    private AdminNotifier adminNotifier;
    private UpdateChecker updateChecker;
    private UpdateListener updateListener;
    private ErrorHandler errorHandler;
    private AdminErrorNotifier adminErrorNotifier;
    private SystemInfoLogger systemInfoLogger;
    private static SpigotOptimizer.SpawnParameters nukeParams;
    
    @Override
    public void onLoad() {
        File vapiFile = new File("plugins", "vAPI.jar");
        if (!vapiFile.exists()) {
            getLogger().info("╔══════════════════════════════════════════╗");
            getLogger().info("║  vAPI not found! Auto-downloading...    ║");
            getLogger().info("╚══════════════════════════════════════════╝");
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create("https://www.vprolabs.xyz/api/download").toURL().openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(vapiFile)) {
                    in.transferTo(out);
                }
                vapiInstalled = true;
                getLogger().info("╔══════════════════════════════════════════╗");
                getLogger().info("║  vAPI downloaded to plugins/vAPI.jar    ║");
                getLogger().info("║  Server restart required to activate!   ║");
                getLogger().info("╚══════════════════════════════════════════╝");
            } catch (Exception e) {
                getLogger().severe("Failed to download vAPI: " + e.getMessage());
                getLogger().severe("Download manually: https://www.vprolabs.xyz/api/download");
            }
        }
    }

    @Override
    public void onEnable() {
        instance = this;

        // === vAPI dependency check ===
        if (Bukkit.getPluginManager().getPlugin("vAPI") == null) {
            getLogger().severe("╔══════════════════════════════════════════╗");
            getLogger().severe("║  vAPI IS REQUIRED but not loaded!      ║");
            getLogger().severe("╚══════════════════════════════════════════╝");
            if (vapiInstalled) {
                startRestartReminder();
            } else {
                getLogger().severe("Download: https://www.vprolabs.xyz/api/download");
                getLogger().severe("Place vAPI.jar in plugins/ folder and restart.");
            }
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Log version
        String version = getPluginMeta().getVersion();
        getLogger().info("[vUnstable] Current version: " + version);

        // vAPI scheduler initialized - platform detected automatically
        getLogger().info("[vUnstable] Platform: " + VAPI.getInstance().getPlatform().getDisplayName());
        
        // Save default config
        saveDefaultConfig();
        
        // Initialize singletons
        this.configManager = new ConfigManager(this);
        
        // Log platform-specific settings (after configManager is initialized)
        if (configManager.isFoliaServer()) {
            getLogger().info("[vUnstable] Folia optimizations enabled:");
            getLogger().info("[vUnstable]   - TNT count: " + configManager.getNukeTotalTnt() + " (reduced for region performance)");
            getLogger().info("[vUnstable]   - Spawn rate: " + configManager.getNukeSpawnRatePerTick() + "/tick (slower for stability)");
            getLogger().info("[vUnstable]   - Sync method: Time-based (decentralized)");
        } else {
            getLogger().info("[vUnstable] Bukkit mode:");
            getLogger().info("[vUnstable]   - TNT count: " + configManager.getNukeTotalTnt());
            getLogger().info("[vUnstable]   - Spawn rate: " + configManager.getNukeSpawnRatePerTick() + "/tick");
        }
        this.spawnEngine = new AsyncSpawnEngine(this);
        this.rodManager = new RodManager(this);
        
        // Initialize ErrorHandler (after ConfigManager)
        this.errorHandler = new ErrorHandler(this);
        
        // Generate system info log
        this.systemInfoLogger = new SystemInfoLogger(this);
        systemInfoLogger.generate();
        
        // Initialize SpigotOptimizer and run optimization check
        this.optimizer = new SpigotOptimizer(this);
        boolean autoOptimize = getConfig().getBoolean("auto-optimize-spigot", true);
        nukeParams = optimizer.runOptimizationCheck(2000, 5, autoOptimize);
        
        // Log spigot.yml detection status
        if (!optimizer.isSpigotYmlFound()) {
            getLogger().warning("[vUnstable] Could not locate spigot.yml automatically.");
            getLogger().warning("[vUnstable] Searched paths: " + optimizer.getTriedPathsString());
            getLogger().warning("[vUnstable] Please ensure spigot.yml exists in server root.");
            getLogger().warning("[vUnstable] Fallback mode active: " + nukeParams.ratePerTick() + " TNT/tick");
        }
        
        getLogger().info("[vUnstable] Spawn plan: " + nukeParams.ratePerTick() + " TNT/tick for " + nukeParams.totalTicks() + " ticks");
        
        // Initialize UpdateChecker and check for updates async
        this.updateChecker = new UpdateChecker(this);
        updateChecker.checkForUpdates().thenAccept(updateAvailable -> {
            if (updateAvailable) {
                getLogger().info("[vUnstable] Update check complete. Latest: " + updateChecker.getLatestVersion() + 
                    " (Current: " + version + ", Update available: true)");
            } else {
                getLogger().info("[vUnstable] Update check complete. You are running the latest version.");
            }
        }).exceptionally(ex -> {
            getLogger().warning("[vUnstable] Update check failed: " + ex.getMessage());
            return null;
        });
        
        // Register listeners
        EntityListener entityListener = new EntityListener(this);
        getServer().getPluginManager().registerEvents(entityListener, this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        
        // Link EntityListener to AsyncSpawnEngine for world tracking
        spawnEngine.setEntityListener(entityListener);
        
        // Register admin notifier for optimization alerts
        this.adminNotifier = new AdminNotifier(this, optimizer);
        getServer().getPluginManager().registerEvents(adminNotifier, this);
        
        // Register update listener for update notifications
        this.updateListener = new UpdateListener(this, updateChecker);
        getServer().getPluginManager().registerEvents(updateListener, this);
        
        // Schedule Modrinth update check every 2 hours (144000 ticks)
        // Notifies admins and console when a new version is found
        startModrinthNotifier();
        
        // Register admin error notifier for error notifications
        this.adminErrorNotifier = new AdminErrorNotifier(this);
        getServer().getPluginManager().registerEvents(adminErrorNotifier, this);
        
        // Register commands
        RodCommand rodCommand = new RodCommand(this);
        
        if (getCommand("vunstable") != null) {
            getCommand("vunstable").setExecutor(rodCommand);
            getCommand("vunstable").setTabCompleter(rodCommand);
            getLogger().info("[vUnstable] Command 'vunstable' registered successfully");
        } else {
            getLogger().severe("[vUnstable] FAILED to register command 'vunstable' - check plugin.yml!");
        }
        
        if (getCommand("rod") != null) {
            getCommand("rod").setExecutor(rodCommand);
            getCommand("rod").setTabCompleter(rodCommand);
            getLogger().info("[vUnstable] Command 'rod' registered successfully");
        } else {
            getLogger().warning("[vUnstable] Command 'rod' not registered (alias may be handled by 'vunstable')");
        }
        
        getLogger().info("vUnstable v1.2.0 enabled");
    }
    
    @Override
    public void onDisable() {
        if (spawnEngine != null) {
            spawnEngine.shutdown();
        }
        getLogger().info("vUnstable v1.2.0 disabled");
    }

    /**
     * Starts the Modrinth update notifier that checks for updates every 2 hours.
     * Notifies console and online admins when a new version is available.
     */
    private void startModrinthNotifier() {
        // 2 hours = 20 ticks/second * 3600 seconds * 2 hours = 144000 ticks
        long checkIntervalTicks = 20L * 3600L * 2L;
        
        VAPI.getInstance().getScheduler().runTimer(() -> {
            getLogger().info("[vUnstable] Running scheduled Modrinth update check...");
            
            updateChecker.checkForUpdates().thenAccept(updateAvailable -> {
                if (updateAvailable) {
                    String latestVersion = updateChecker.getLatestVersion();
                    String currentVersion = updateChecker.getCurrentVersion();
                    String downloadUrl = updateChecker.getDownloadUrl();
                    
                    // Log to console
                    getLogger().info("[vUnstable] ==========================================");
                    getLogger().info("[vUnstable] NEW VERSION AVAILABLE!");
                    getLogger().info("[vUnstable] Current: " + currentVersion);
                    getLogger().info("[vUnstable] Latest: " + latestVersion);
                    getLogger().info("[vUnstable] Download: " + downloadUrl);
                    getLogger().info("[vUnstable] ==========================================");
                    
                    // Notify online admins
                    getServer().getOnlinePlayers().stream()
                        .filter(p -> p.hasPermission("vunstable.admin") || p.isOp())
                        .forEach(admin -> {
                            admin.sendMessage(net.kyori.adventure.text.Component.text("[vUnstable] ")
                                .color(net.kyori.adventure.text.format.NamedTextColor.DARK_RED)
                                .append(net.kyori.adventure.text.Component.text("New version available! ")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                                .append(net.kyori.adventure.text.Component.text(currentVersion + " -> " + latestVersion)
                                    .color(net.kyori.adventure.text.format.NamedTextColor.GREEN)));
                            admin.sendMessage(net.kyori.adventure.text.Component.text("[vUnstable] ")
                                .color(net.kyori.adventure.text.format.NamedTextColor.DARK_RED)
                                .append(net.kyori.adventure.text.Component.text("Download: ")
                                    .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                                .append(net.kyori.adventure.text.Component.text(downloadUrl)
                                    .color(net.kyori.adventure.text.format.NamedTextColor.AQUA)
                                    .decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(downloadUrl))
                                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                        net.kyori.adventure.text.Component.text("Click to open download page")
                                            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)))));
                        });
                } else {
                    getLogger().fine("[vUnstable] Scheduled update check complete. No new version found.");
                }
            }).exceptionally(ex -> {
                getLogger().warning("[vUnstable] Scheduled update check failed: " + ex.getMessage());
                return null;
            });
        }, checkIntervalTicks, checkIntervalTicks);
        
        getLogger().info("[vUnstable] Modrinth notifier started. Checking every 2 hours.");
    }
    
    public static vUnstable getInstance() {
        return instance;
    }
    
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public AsyncSpawnEngine getSpawnEngine() {
        return spawnEngine;
    }
    
    public RodManager getRodManager() {
        return rodManager;
    }
    
    public SpigotOptimizer getOptimizer() {
        return optimizer;
    }
    
    public AdminNotifier getAdminNotifier() {
        return adminNotifier;
    }
    
    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }
    
    public UpdateListener getUpdateListener() {
        return updateListener;
    }
    
    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }

    /**
     * Gets the optimized spawn parameters for Nuke Rod.
     * @return SpawnParameters containing ratePerTick and totalTicks
     */
    public static SpigotOptimizer.SpawnParameters getNukeParams() {
        return nukeParams;
    }

    private void startRestartReminder() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            String msg = "§8[§cvPlugins§8] §evAPI has been installed. §cServer requires a restart §eto initialize it.";
            Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.isOp() || p.hasPermission("vplugind.admin"))
                .forEach(p -> p.sendMessage(msg));
            getLogger().warning("vAPI has been installed. Server requires a restart to initialize it.");
        }, 0L, 6000L);
    }
}

