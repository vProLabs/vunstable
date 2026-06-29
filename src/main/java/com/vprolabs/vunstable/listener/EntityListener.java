package com.vprolabs.vunstable.listener;

import com.vprolabs.vunstable.config.ConfigManager;
import com.vprolabs.vunstable.vUnstable;
import org.bukkit.block.Block;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.vprolabs.vapi.VAPI;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * EntityListener - Handles entity events for Nuke/Stab TNT.
 * 
 * Features:
 * - Prevents block drops from vUnstable explosions (but blocks still break)
 * - Tracks TNT via metadata
 * - v1.2.0: Removed redundant ground detection (handled by NukeRod)
 * - Full Folia thread safety for all block operations
 */
public class EntityListener implements Listener {
    
    private final JavaPlugin plugin;
    private final Set<UUID> activeNukeWorlds = new HashSet<>();
    
    // Metadata keys
    public static final String META_NUKE_ROD = "vunstable_nuke_rod";
    public static final String META_STAB_ROD = "vunstable_stab_rod";
    public static final String META_NUKE_EXPLOSION = "vunstable_nuke_explosion";
    public static final String META_NUKE_SYNC_DELAY = "vunstable_nuke_sync_delay";
    public static final String META_NUKE_RING_INDEX = "vunstable_nuke_ring_index";
    public static final String META_NUKE_PENDING_EXPLOSION = "vunstable_nuke_pending";
    
    public EntityListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Mark a world as having active Nuke TNT.
     * Called by AsyncSpawnEngine when spawning Nuke TNT.
     */
    public void markNukeActive(org.bukkit.World world) {
        if (world != null) {
            activeNukeWorlds.add(world.getUID());
        }
    }
    
    /**
     * Check if entity has our metadata key.
     */
    private boolean hasMetadata(TNTPrimed tnt, String key) {
        if (!tnt.hasMetadata(key)) return false;
        for (MetadataValue mv : tnt.getMetadata(key)) {
            if (mv.getOwningPlugin() == plugin) return true;
        }
        return false;
    }
    
    /**
     * Handle EntityExplodeEvent - mark blocks for no drops.
     * We do NOT cancel the event - we want blocks to break, just not drop items.
     * Event handlers run on the correct thread automatically.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) return;
        
        // Check if this is our TNT
        boolean isNuke = hasMetadata(tnt, META_NUKE_ROD);
        boolean isStab = hasMetadata(tnt, META_STAB_ROD);
        
        if (!isNuke && !isStab) return;
        
        String type = isNuke ? "Nuke" : "Stab";
        int blockCount = event.blockList().size();
        
        plugin.getLogger().fine("[vUnstable] " + type + " explosion processing: " + blockCount + " blocks");
        
        // Mark all blocks in explosion for no drops
        for (Block block : event.blockList()) {
            block.setMetadata(META_NUKE_EXPLOSION, new FixedMetadataValue(plugin, true));
        }
        
        // Ensure yield is proper (in case it wasn't set during spawn)
        event.setYield(4.0f);
    }
    
    /**
     * Prevent item drops from blocks broken by our explosions.
     * This is Paper/Spigot 1.21+ specific event.
     * Only cancels if config remove-block-drops is true (default).
     * Event handlers run on the correct thread automatically.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockDropItem(BlockDropItemEvent event) {
        // Check config - if false, allow vanilla drops
        if (!ConfigManager.getInstance().shouldRemoveBlockDrops()) {
            return;
        }
        
        Block block = event.getBlock();
        
        // Check if this block was marked by our explosion
        if (!block.hasMetadata(META_NUKE_EXPLOSION)) return;
        
        // Cancel the drops
        event.setCancelled(true);
        
        // Clean up metadata
        block.removeMetadata(META_NUKE_EXPLOSION, plugin);
        
        plugin.getLogger().fine("[vUnstable] Cancelled item drops for block at " + block.getLocation());
    }
    
    /**
     * Fallback for older versions: manually break blocks without drops.
     * This runs after explosion to ensure blocks are broken without drops.
     * Only runs if config remove-block-drops is true (default).
     * v1.2.0: Uses location-based scheduling for Folia thread safety.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityExplodeMonitor(EntityExplodeEvent event) {
        // Check config - if false, allow vanilla drops
        if (!ConfigManager.getInstance().shouldRemoveBlockDrops()) {
            return;
        }
        
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof TNTPrimed tnt)) return;
        
        boolean isNuke = hasMetadata(tnt, META_NUKE_ROD);
        boolean isStab = hasMetadata(tnt, META_STAB_ROD);
        
        if (!isNuke && !isStab) return;
        
        // For any remaining blocks that might drop items, force them to air
        // This is a safety net for versions without BlockDropItemEvent
        // Use the first block's location to determine region for Folia scheduling
        if (!event.blockList().isEmpty()) {
            Block firstBlock = event.blockList().get(0);
            VAPI.getInstance().getScheduler().runAtLocation(firstBlock.getLocation(), () -> {
                for (Block block : event.blockList()) {
                    if (block.hasMetadata(META_NUKE_EXPLOSION)) {
                        block.setType(org.bukkit.Material.AIR, false);
                        block.removeMetadata(META_NUKE_EXPLOSION, plugin);
                    }
                }
            });
        }
    }
}
