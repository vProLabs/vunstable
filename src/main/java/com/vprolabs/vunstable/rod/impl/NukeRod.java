package com.vprolabs.vunstable.rod.impl;

import com.vprolabs.vunstable.config.ConfigManager;
import com.vprolabs.vunstable.engine.AsyncSpawnEngine;
import com.vprolabs.vunstable.rod.RodManager;
import com.vprolabs.vunstable.vUnstable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.vprolabs.vapi.VAPI;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NukeRod - The Orbital Strike Cannon.
 * 
 * v1.2.0: Decentralized time-based synchronization for Folia compatibility.
 * - Uses absolute timestamps for explosion synchronization
 * - Each TNT has its own entity-local timer
 * - No global UUID tracking (eliminates race conditions)
 * - Platform-specific optimizations (Folia: 1000 TNT, Bukkit: 2000 TNT)
 */
public class NukeRod implements RodManager.Rod {
    
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final AsyncSpawnEngine spawnEngine;
    
    // Simple counter for active nukes (queue management only)
    private static final AtomicInteger activeNukeCount = new AtomicInteger(0);
    private static final Deque<NukeRequest> nukeQueue = new ArrayDeque<>();
    
    private static final int NUKE_COOLDOWN_TICKS = 120; // 6 seconds cleanup
    
    public NukeRod(JavaPlugin plugin) {
        this.plugin = plugin;
        this.config = ConfigManager.getInstance();
        this.spawnEngine = ((vUnstable) plugin).getSpawnEngine();
    }
    
    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        
        if (meta != null) {
            String customName = config.getNukeRodName();
            meta.displayName(Component.text(customName).color(TextColor.color(0xFF0000)));
            
            if (config.isNukeRodEnchanted()) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            }
            
            item.setItemMeta(meta);
        }
        
        if (meta instanceof org.bukkit.inventory.meta.Damageable damageable) {
            damageable.setDamage(1);
            item.setItemMeta(damageable);
        }
        return item;
    }
    
    @Override
    public void activate(Location target, Player player) {
        int maxConcurrent = config.getNukeMaxConcurrent();
        int maxQueue = config.getNukeQueueSize();
        
        if (activeNukeCount.get() >= maxConcurrent) {
            if (nukeQueue.size() >= maxQueue) {
                plugin.getLogger().fine("[vUnstable] Nuke dropped: Queue full (" + nukeQueue.size() + "/" + maxQueue + ")");
                return;
            }
            
            nukeQueue.offer(new NukeRequest(target, player));
            plugin.getLogger().info("[vUnstable] Nuke queued. Queue: " + nukeQueue.size() + "/" + maxQueue);
            return;
        }
        
        executeNuke(target, player);
    }
    
    /**
     * Execute a nuke with platform-specific synchronization.
     * Folia: Ground-based sync (wait for all TNT to land, then explode together)
     * Bukkit: Time-based sync (fixed timer)
     */
    private void executeNuke(Location target, Player player) {
        try {
            double centerX = target.getX();
            double centerZ = target.getZ();
            double spawnY = target.getY() + config.getNukeSpawnHeight();
            World world = target.getWorld();
            
            plugin.getLogger().info("[vUnstable] NUKE ACTIVATED at X" + centerX + " Z" + centerZ + " Y" + spawnY);
            
            activeNukeCount.incrementAndGet();
            
            // Get platform-specific settings
            int totalTnt = config.getNukeTotalTnt();
            // Use optimized spawn rate from SpigotOptimizer if available
            int ratePerTick = config.getNukeSpawnRatePerTick();
            var nukeParams = vUnstable.getNukeParams();
            if (nukeParams != null) {
                ratePerTick = nukeParams.ratePerTick();
            }
            
            int rings = config.getNukeRings();
            double minRadius = config.getNukeMinRadius();
            double maxRadius = config.getNukeMaxRadius();
            double radiusStep = (maxRadius - minRadius) / (rings - 1);
            int tntPerRing = totalTnt / rings;
            
            int tntPerTick = ratePerTick;
            int ticksBetweenRings = Math.max(1, (tntPerRing + tntPerTick - 1) / tntPerTick);
            
            boolean syncEnabled = config.isNukeSyncExplosions();
            int baseDelay = config.getNukeBaseDelayTicks();
            
            // Generate unique nuke ID for ground-based sync
            final String nukeId = "nuke_" + UUID.randomUUID().toString().substring(0, 8);
            final boolean isFolia = config.isFoliaServer();
            
            // Initialize tracking for ground-based sync (both platforms)
            if (syncEnabled) {
                spawnEngine.initNukeTracking(nukeId, totalTnt);
            }
            
            // Record spawn start time for global 4-second timeout
            final long spawnStartTimeMs = System.currentTimeMillis();
            
            // Folia-optimized velocity: faster fall for quicker ground detection
            double velocityY = config.getNukeVelocityY();
            if (isFolia) {
                // Increase fall speed by 50% for Folia (faster ground contact)
                velocityY = velocityY * 1.5;
            }
            final double finalVelocityY = velocityY;
            
            int maxConcurrent = config.getNukeMaxConcurrent();
            plugin.getLogger().info("[vUnstable] Spawning " + totalTnt + " TNT in " + rings + 
                " rings | Rate: " + ratePerTick + "/tick | Sync: " + syncEnabled + 
                " | Mode: Ground-based (4s failsafe)" +
                " | Active: " + activeNukeCount.get() + "/" + maxConcurrent + 
                " | Queue: " + nukeQueue.size());
            
            // Spawn all rings with ground-based sync
            for (int ring = 0; ring < rings; ring++) {
                double radius = minRadius + (ring * radiusStep);
                
                final int finalRing = ring;
                final int ringNum = ring + 1;
                
                VAPI.getInstance().getScheduler().runLater(() -> {
                    try {
                        spawnEngine.queueRingSpawnsSync(
                            centerX, centerZ, spawnY, radius, tntPerRing,
                            finalVelocityY, config.getNukeFuseTicks(), 
                            world, finalRing, spawnStartTimeMs, nukeId);
                        
                        plugin.getLogger().fine("[vUnstable] Ring " + ringNum + "/" + rings + 
                            " spawned");
                            
                    } catch (Exception e) {
                        com.vprolabs.vunstable.util.ErrorHandler.getInstance().handle(e, 
                            "NukeRod.activate() [ring " + ringNum + "]", 
                            "NukeRod", "activate", 82, player, target, 
                            "Failed to queue ring " + ringNum);
                    }
                }, ring * ticksBetweenRings);
            }
            
            // Schedule cleanup after max time
            VAPI.getInstance().getScheduler().runLater(() -> {
                activeNukeCount.decrementAndGet();
                
                // Clean up tracking
                if (syncEnabled) {
                    spawnEngine.cleanupNukeTracking(nukeId);
                }
                
                processQueue();
                
                int maxConcurrentFinal = config.getNukeMaxConcurrent();
                plugin.getLogger().info("[vUnstable] Nuke complete. Active: " + activeNukeCount.get() + 
                    "/" + maxConcurrentFinal + " | Queue: " + nukeQueue.size());
            }, NUKE_COOLDOWN_TICKS);
            
            if (syncEnabled) {
                plugin.getLogger().info("[vUnstable] Ground-based sync active. TNT will explode when all land (4s failsafe).");
            }
            
        } catch (Exception e) {
            activeNukeCount.decrementAndGet();
            com.vprolabs.vunstable.util.ErrorHandler.getInstance().handle(e, 
                "NukeRod.activate()", "NukeRod", "activate", 82, player, target, 
                "Nuke spawn attempt");
            processQueue();
        }
    }
    
    private void processQueue() {
        VAPI.getInstance().getScheduler().runLater(() -> {
            NukeRequest next = nukeQueue.poll();
            if (next != null) {
                plugin.getLogger().info("[vUnstable] Processing queued nuke. Remaining in queue: " + nukeQueue.size());
                executeNuke(next.target, next.player);
            }
        }, 5L);
    }
    
    @Override
    public String getName() {
        return "Nuke";
    }
    
    public static int getActiveNukeCount() {
        return activeNukeCount.get();
    }
    
    public static int getQueueSize() {
        return nukeQueue.size();
    }
    
    private static class NukeRequest {
        final Location target;
        final Player player;
        
        NukeRequest(Location target, Player player) {
            this.target = target;
            this.player = player;
        }
    }
}
