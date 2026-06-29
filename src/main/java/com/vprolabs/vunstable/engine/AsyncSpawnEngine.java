package com.vprolabs.vunstable.engine;

import com.vprolabs.vunstable.config.ConfigManager;
import com.vprolabs.vunstable.listener.EntityListener;
import com.vprolabs.vunstable.vUnstable;
import xyz.vprolabs.vapi.VAPI;
import xyz.vprolabs.vapi.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AsyncSpawnEngine - Handles TNT spawning with rate limiting.
 * 
 * v1.2.0: Decentralized time-based synchronization for Folia.
 * - Each TNT has its own entity-local timer
 * - Absolute timestamp-based explosion synchronization
 * - No global UUID tracking needed
 */
public class AsyncSpawnEngine {
    
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final Deque<SpawnRequest> spawnQueue;
    private EntityListener entityListener;
    
    // Ground-based sync tracking
    // nukeId -> Set of TNT UUIDs that haven't landed yet
    private final Map<String, Set<UUID>> pendingNukes = new ConcurrentHashMap<>();
    // nukeId -> nuke total count
    private final Map<String, Integer> nukeTotalCounts = new ConcurrentHashMap<>();
    // nukeId -> count of TNT that exploded via failsafe (for summary)
    private final Map<String, Integer> nukeFailsafeCounts = new ConcurrentHashMap<>();
    
    // NMS MethodHandles
    private MethodHandle getHandle;
    private MethodHandle addEntity;
    private MethodHandle setDeltaMovement;
    private MethodHandle setFuse;
    private MethodHandle setYield;
    private Object tntConstructor;
    private boolean nmsAvailable = false;
    
    public AsyncSpawnEngine(JavaPlugin plugin) {
        this.plugin = plugin;
        this.config = ConfigManager.getInstance();
        this.spawnQueue = new ArrayDeque<>();
        initNMS();
        startSpawnTask();
    }
    
    public void setEntityListener(EntityListener listener) {
        this.entityListener = listener;
    }
    
    private void initNMS() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            
            Class<?> craftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            getHandle = lookup.findVirtual(craftWorld, "getHandle", MethodType.methodType(Class.forName("net.minecraft.server.level.ServerLevel")));
            
            Class<?> primedTnt = Class.forName("net.minecraft.world.entity.item.PrimedTnt");
            Class<?> levelClass = Class.forName("net.minecraft.world.level.Level");
            
            for (var ctor : primedTnt.getConstructors()) {
                var params = ctor.getParameterTypes();
                if (params.length == 5 && params[0] == levelClass) {
                    tntConstructor = lookup.unreflectConstructor(ctor);
                    break;
                }
            }
            
            if (tntConstructor == null) {
                plugin.getLogger().warning("[vUnstable] No 5-param TNT constructor found");
                return;
            }
            
            setDeltaMovement = lookup.findVirtual(primedTnt, "setDeltaMovement", MethodType.methodType(void.class, double.class, double.class, double.class));
            setFuse = lookup.findVirtual(primedTnt, "setFuse", MethodType.methodType(void.class, int.class));
            
            try {
                setYield = lookup.findVirtual(primedTnt, "setYield", MethodType.methodType(void.class, float.class));
            } catch (Exception e) {
                plugin.getLogger().fine("[vUnstable] NMS setYield not available");
            }
            
            Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> entityClass = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> spawnReason = Class.forName("org.bukkit.event.entity.CreatureSpawnEvent$SpawnReason");
            addEntity = lookup.findVirtual(serverLevel, "addFreshEntity", MethodType.methodType(boolean.class, entityClass, spawnReason));
            
            nmsAvailable = true;
            plugin.getLogger().info("[vUnstable] AsyncSpawnEngine: NMS Mode (MethodHandles)");
        } catch (Exception e) {
            plugin.getLogger().warning("[vUnstable] AsyncSpawnEngine: Bukkit Mode - " + e.getMessage());
            nmsAvailable = false;
        }
    }
    
    private void startSpawnTask() {
        int rate = config.getMaxTntPerTick();
        var params = vUnstable.getNukeParams();
        if (params != null) {
            rate = params.ratePerTick();
        }
        
        final int spawnRate = rate;
        VAPI.getInstance().getScheduler().runTimer(() -> processQueue(spawnRate), 0L, 1L);
    }
    
    private void processQueue(int max) {
        int processed = 0;
        while (processed < max && !spawnQueue.isEmpty()) {
            SpawnRequest req = spawnQueue.poll();
            if (req != null) {
                try {
                    spawnTNT(req);
                    processed++;
                } catch (Exception e) {
                    com.vprolabs.vunstable.util.ErrorHandler.getInstance().handle(e, 
                        "AsyncSpawnEngine.processQueue()", 
                        "AsyncSpawnEngine", "processQueue", 142,
                        null, req.location, 
                        "TNT spawn failed at " + req.location);
                }
            }
        }
    }
    
    private void spawnTNT(SpawnRequest req) {
        VAPI.getInstance().getScheduler().runAtLocation(req.location, () -> {
            // Try NMS first
            if (nmsAvailable) {
                try {
                    Object level = getHandle.invoke(req.location.getWorld());
                    Object tnt = ((MethodHandle) tntConstructor).invoke(level, req.location.getX(), req.location.getY(), req.location.getZ(), null);
                    setDeltaMovement.invoke(tnt, req.velocity.getX(), req.velocity.getY(), req.velocity.getZ());
                    setFuse.invoke(tnt, req.fuseTicks);
                    
                    if (setYield != null) {
                        setYield.invoke(tnt, req.yield);
                    }
                    
                    Class<?> spawnReason = Class.forName("org.bukkit.event.entity.CreatureSpawnEvent$SpawnReason");
                    boolean added = (boolean) addEntity.invoke(level, tnt, spawnReason.getField("CUSTOM").get(null));
                    
                    if (added) {
                        if (req.isNuke && entityListener != null) {
                            entityListener.markNukeActive(req.location.getWorld());
                        }
                        
                        // CRITICAL FIX: Get the Bukkit entity and attach metadata + timer for Nuke TNT
                        if (req.isNuke || req.isStab) {
                            try {
                                Object bukkitEntity = tnt.getClass().getMethod("getBukkitEntity").invoke(tnt);
                                if (bukkitEntity instanceof TNTPrimed tntEntity) {
                                    // Attach metadata
                                    if (req.isNuke) {
                                        tntEntity.setMetadata(EntityListener.META_NUKE_ROD, new FixedMetadataValue(plugin, true));
                                        tntEntity.setMetadata(EntityListener.META_NUKE_RING_INDEX, new FixedMetadataValue(plugin, req.ringIndex));
                                        // Attach nukeId for ground-based sync
                                        if (req.nukeId != null) {
                                            tntEntity.setMetadata("nukeId", new FixedMetadataValue(plugin, req.nukeId));
                                        }
                                    } else if (req.isStab) {
                                        tntEntity.setMetadata(EntityListener.META_STAB_ROD, new FixedMetadataValue(plugin, true));
                                    }
                                    
                                    // Attach explosion timer for Nuke TNT with sync
                                    if (req.isNuke) {
                                        attachEntityTimer(tntEntity, req.spawnTimeMs, req.nukeId);
                                    }
                                }
                            } catch (Exception metaEx) {
                                plugin.getLogger().warning("[vUnstable] Failed to attach metadata to NMS TNT: " + metaEx.getMessage());
                            }
                        }
                        return;
                    }
                } catch (Throwable e) {
                    com.vprolabs.vunstable.util.ErrorHandler.getInstance().handle(e, 
                        "AsyncSpawnEngine.spawnTNT() [NMS]", 
                        "AsyncSpawnEngine", "spawnTNT", 156,
                        null, req.location, 
                        "NMS TNT spawn failed, falling back to Bukkit API");
                }
            }
            
            // Bukkit fallback
            try {
                TNTPrimed tnt = req.location.getWorld().spawn(req.location, TNTPrimed.class, entity -> {
                    entity.setVelocity(req.velocity);
                    entity.setFuseTicks(req.fuseTicks);
                    entity.setYield(req.yield);
                    
                    if (req.isNuke) {
                        entity.setMetadata(EntityListener.META_NUKE_ROD, new FixedMetadataValue(plugin, true));
                        entity.setMetadata(EntityListener.META_NUKE_RING_INDEX, new FixedMetadataValue(plugin, req.ringIndex));
                        // Attach nukeId for ground-based sync
                        if (req.nukeId != null) {
                            entity.setMetadata("nukeId", new FixedMetadataValue(plugin, req.nukeId));
                        }
                    } else if (req.isStab) {
                        entity.setMetadata(EntityListener.META_STAB_ROD, new FixedMetadataValue(plugin, true));
                    }
                }, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
                
                if (tnt != null) {
                    if (req.isNuke && entityListener != null) {
                        entityListener.markNukeActive(req.location.getWorld());
                    }
                    
                    // Attach entity-local timer for decentralized explosion handling
                    if (req.isNuke) {
                        attachEntityTimer(tnt, req.spawnTimeMs, req.nukeId);
                    }
                }
            } catch (Exception e) {
                com.vprolabs.vunstable.util.ErrorHandler.getInstance().handle(e, 
                    "AsyncSpawnEngine.spawnTNT() [Bukkit]", 
                    "AsyncSpawnEngine", "spawnTNT", 202,
                    null, req.location, 
                    "Bukkit TNT spawn failed at " + req.location);
            }
        });
    }
    
    /**
     * Attach a decentralized timer to a TNT entity.
     * Folia: Ground-based sync (wait for all TNT to land, then explode together)
     * Bukkit: Time-based sync (fixed timer)
     */
    private void attachEntityTimer(TNTPrimed tnt, long spawnTimeMs, String nukeId) {
        // Track this TNT in the pending set for ground-based sync
        if (nukeId != null) {
            Set<UUID> pending = pendingNukes.get(nukeId);
            if (pending != null) {
                pending.add(tnt.getUniqueId());
            }
        }
        
        // Ground detection task - runs every tick
        final ScheduledTask[] taskHolder = new ScheduledTask[1];
        final boolean[] wasFailsafe = { false };
        
        taskHolder[0] = VAPI.getInstance().getScheduler().runTimerAtEntity(tnt, () -> {
            if (nukeId == null) return;
            
            // Ground detection - freeze when landed
            if (tnt.isOnGround() && !tnt.hasMetadata("landed")) {
                tnt.setMetadata("landed", new FixedMetadataValue(plugin, true));
                freezeTNT(tnt);
                
                // Mark this TNT as landed
                Set<UUID> pending = pendingNukes.get(nukeId);
                if (pending != null) {
                    pending.remove(tnt.getUniqueId());
                    
                    // Check if all TNT have landed
                    if (pending.isEmpty()) {
                        // ALL TNT LANDED! Trigger explosion immediately
                        int total = nukeTotalCounts.getOrDefault(nukeId, 0);
                        int failsafe = nukeFailsafeCounts.getOrDefault(nukeId, 0);
                        int landed = total - failsafe;
                        if (failsafe > 0) {
                            plugin.getLogger().info("[vUnstable] All TNT ready! " + landed + " landed, " + failsafe + " via failsafe. Exploding now...");
                        } else {
                            plugin.getLogger().info("[vUnstable] All " + total + " TNT landed! Exploding now...");
                        }
                        triggerNukeExplosion(nukeId);
                    }
                }
            }
            
            // Global 4-second timeout from spawn
            long elapsedMs = System.currentTimeMillis() - spawnTimeMs;
            if (elapsedMs > 4000 && !wasFailsafe[0]) {
                wasFailsafe[0] = true;
                // Track failsafe explosion
                Integer current = nukeFailsafeCounts.get(nukeId);
                if (current != null) {
                    nukeFailsafeCounts.put(nukeId, current + 1);
                }
                forceExplode(tnt);
                if (taskHolder[0] != null) {
                    taskHolder[0].cancel();
                }
            }
        }, 1L, 1L);
    }
    
    /**
     * Trigger synchronized explosion for all TNT in a nuke (Folia only).
     * Finds all TNT with this nukeId and explodes them immediately.
     */
    private void triggerNukeExplosion(String nukeId) {
        // Clean up tracking
        pendingNukes.remove(nukeId);
        nukeTotalCounts.remove(nukeId);
        
        // Find and explode all TNT with this nukeId
        // This runs on each entity's own timer, so each TNT will explode itself
        // when it detects the "explodeNow" metadata
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (TNTPrimed tnt : world.getEntitiesByClass(TNTPrimed.class)) {
                if (tnt.hasMetadata("nukeId")) {
                    for (org.bukkit.metadata.MetadataValue mv : tnt.getMetadata("nukeId")) {
                        if (mv.asString().equals(nukeId) && !tnt.hasMetadata("exploded")) {
                            tnt.setMetadata("exploded", new FixedMetadataValue(plugin, true));
                            forceExplode(tnt);
                            break;
                        }
                    }
                }
            }
        }
    }
    
    private void forceExplode(TNTPrimed tnt) {
        // Try NMS first (most reliable)
        try {
            Object nmsTnt = tnt.getClass().getMethod("getHandle").invoke(tnt);
            java.lang.reflect.Method setFuseMethod = nmsTnt.getClass().getMethod("setFuse", int.class);
            setFuseMethod.invoke(nmsTnt, 0);
            return;
        } catch (Exception ignored) {}
        
        // Fall back to Bukkit API
        try {
            tnt.setFuseTicks(0);
        } catch (Exception e2) {
            // Last resort: create explosion at location
            try {
                tnt.getLocation().getWorld().createExplosion(tnt.getLocation(), 4.0f, false, true);
                tnt.remove();
            } catch (Exception e3) {
                plugin.getLogger().severe("[vUnstable] CRITICAL: All explosion methods failed for TNT " + tnt.getUniqueId());
            }
        }
    }
    
    private void freezeTNT(TNTPrimed tnt) {
        try {
            tnt.setVelocity(new Vector(0, 0, 0));
            
            try {
                Object nmsTnt = tnt.getClass().getMethod("getHandle").invoke(tnt);
                java.lang.reflect.Method setDelta = nmsTnt.getClass().getMethod("setDeltaMovement", double.class, double.class, double.class);
                setDelta.invoke(nmsTnt, 0.0, 0.0, 0.0);
                java.lang.reflect.Field hasImpulse = nmsTnt.getClass().getField("hasImpulse");
                hasImpulse.setBoolean(nmsTnt, false);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            plugin.getLogger().warning("[vUnstable] Failed to freeze TNT: " + e.getMessage());
        }
    }
    
    public void queueSpawn(Location location, Vector velocity, int fuseTicks) {
        spawnQueue.offer(new SpawnRequest(location, velocity, fuseTicks, 4.0f, false, false, 0, 0));
    }
    
    public void queueSpawn(Location location, Vector velocity, int fuseTicks, float yield, boolean isNuke, boolean isStab) {
        spawnQueue.offer(new SpawnRequest(location, velocity, fuseTicks, yield, isNuke, isStab, 0, 0));
    }
    
    /**
     * Queue multiple spawns for a nuke ring with ground-based synchronization.
     * All platforms: Wait for all TNT to land, then explode immediately.
     * 4-second timeout from spawn as failsafe.
     */
    public void queueRingSpawnsSync(double centerX, double centerZ, double spawnY, double radius, int count, 
                                    double velocityY, int fuseTicks, org.bukkit.World world, int ringIndex, long spawnTimeMs, String nukeId) {
        for (int i = 0; i < count; i++) {
            double angle = (2.0 * Math.PI * i) / count;
            double x = centerX + (Math.cos(angle) * radius);
            double z = centerZ + (Math.sin(angle) * radius);
            Location loc = new Location(world, x, spawnY, z);
            spawnQueue.offer(new SpawnRequest(loc, new Vector(0, velocityY, 0), fuseTicks, 4.0f, true, false, ringIndex, spawnTimeMs, nukeId));
        }
    }
    
    /**
     * Initialize tracking for a new nuke (ground-based sync).
     */
    public void initNukeTracking(String nukeId, int totalTnt) {
        pendingNukes.put(nukeId, ConcurrentHashMap.newKeySet());
        nukeTotalCounts.put(nukeId, totalTnt);
        nukeFailsafeCounts.put(nukeId, 0);
    }
    
    /**
     * Clean up tracking for a nuke.
     */
    public void cleanupNukeTracking(String nukeId) {
        pendingNukes.remove(nukeId);
        nukeTotalCounts.remove(nukeId);
        nukeFailsafeCounts.remove(nukeId);
    }
    
    public void queueColumnSpawns(double centerX, double centerZ, int startY, int endY,
                                   double velocityY, int fuseTicks, org.bukkit.World world) {
        for (int y = startY; y >= endY; y--) {
            Location loc = new Location(world, centerX, y, centerZ);
            queueSpawn(loc, new Vector(0, velocityY, 0), fuseTicks, 4.0f, false, true);
        }
    }
    
    public void queueInstantSpawn(Location location, Vector velocity, int fuseTicks, 
                                   float yield, boolean isNuke, boolean isStab) {
        VAPI.getInstance().getScheduler().runAtLocation(location, () -> {
            try {
                TNTPrimed tnt = location.getWorld().spawn(location, TNTPrimed.class, entity -> {
                    entity.setVelocity(velocity);
                    entity.setFuseTicks(fuseTicks);
                    entity.setYield(yield);
                    entity.setGravity(false);
                    
                    if (isNuke) {
                        entity.setMetadata(EntityListener.META_NUKE_ROD, new FixedMetadataValue(plugin, true));
                    } else if (isStab) {
                        entity.setMetadata(EntityListener.META_STAB_ROD, new FixedMetadataValue(plugin, true));
                    }
                }, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
            } catch (Exception e) {
                plugin.getLogger().warning("[vUnstable] Failed to instant spawn TNT: " + e.getMessage());
            }
        });
    }
    
    public void clearSpawnedEntities() {
        // No-op: we no longer track spawned entities centrally
    }
    
    public void shutdown() {
        spawnQueue.clear();
    }
    
    public boolean isNmsAvailable() {
        return nmsAvailable;
    }
    
    private static class SpawnRequest {
        final Location location;
        final Vector velocity;
        final int fuseTicks;
        final float yield;
        final boolean isNuke;
        final boolean isStab;
        final int ringIndex;
        final long spawnTimeMs; // For 4-second timeout calculation
        final String nukeId; // For ground-based sync
        
        SpawnRequest(Location location, Vector velocity, int fuseTicks, float yield, boolean isNuke, boolean isStab, int ringIndex, long spawnTimeMs) {
            this(location, velocity, fuseTicks, yield, isNuke, isStab, ringIndex, spawnTimeMs, null);
        }
        
        SpawnRequest(Location location, Vector velocity, int fuseTicks, float yield, boolean isNuke, boolean isStab, int ringIndex, long spawnTimeMs, String nukeId) {
            this.location = location;
            this.velocity = velocity;
            this.fuseTicks = fuseTicks;
            this.yield = yield;
            this.isNuke = isNuke;
            this.isStab = isStab;
            this.ringIndex = ringIndex;
            this.spawnTimeMs = spawnTimeMs;
            this.nukeId = nukeId;
        }
    }
}
