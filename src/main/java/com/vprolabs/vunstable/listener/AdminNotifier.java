package com.vprolabs.vunstable.listener;

import com.vprolabs.vunstable.config.SpigotOptimizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.vprolabs.vapi.VAPI;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * AdminNotifier - Notifies admins on join about server optimization status.
 * 
 * Only shows message if spigot.yml max-tnt-per-tick is below optimal (400).
 * Messages are limited to once per admin per server session.
 */
public class AdminNotifier implements Listener {
    
    private final JavaPlugin plugin;
    private final SpigotOptimizer optimizer;
    private final Set<UUID> notifiedAdmins = new HashSet<>();
    
    private static final String PERMISSION_ADMIN = "vunstable.admin";
    private static final long DELAY_TICKS = 60; // 3 seconds (20 ticks/second)
    
    public AdminNotifier(JavaPlugin plugin, SpigotOptimizer optimizer) {
        this.plugin = plugin;
        this.optimizer = optimizer;
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Check if player is admin (op or has permission)
        if (!isAdmin(player)) {
            return;
        }
        
        // Check if server is already optimized
        if (optimizer.isOptimized()) {
            return;
        }
        
        // Check if already notified this session
        if (notifiedAdmins.contains(player.getUniqueId())) {
            return;
        }
        
        // Log to console
        plugin.getLogger().info("[vUnstable] Admin '" + player.getName() + "' joined - optimization status: NOT OPTIMIZED");
        
        // Delay notification by 3 seconds
        VAPI.getInstance().getScheduler().runLater(() -> {
            // Double-check player is still online
            if (!player.isOnline()) {
                return;
            }
            
            // Mark as notified
            notifiedAdmins.add(player.getUniqueId());
            
            // Send messages
            sendOptimizationMessage(player);
            
            // Play sound
            try {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 0.5f);
            } catch (Exception ignored) {
                // Sound might not exist in some versions
            }
        }, DELAY_TICKS);
    }
    
    /**
     * Checks if a player is considered an admin.
     */
    private boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission(PERMISSION_ADMIN);
    }
    
    /**
     * Sends the optimization warning message to the admin.
     * Formatted across multiple lines with color coding.
     */
    private void sendOptimizationMessage(Player player) {
        int currentValue = optimizer.getCurrentMaxTntPerTick();
        int requiredValue = SpigotOptimizer.TARGET_RATE;
        
        // Header
        player.sendMessage(Component.text("[vUnstable] ")
            .color(NamedTextColor.DARK_RED)
            .append(Component.text("Server not optimized for Nuke Rod!")
                .color(NamedTextColor.YELLOW)
                .decorate(TextDecoration.BOLD)));
        
        // Current value
        player.sendMessage(Component.text("[vUnstable] ")
            .color(NamedTextColor.DARK_RED)
            .append(Component.text("Current max-tnt-per-tick: ")
                .color(NamedTextColor.YELLOW))
            .append(Component.text(String.valueOf(currentValue))
                .color(NamedTextColor.WHITE)));
        
        // Required value
        player.sendMessage(Component.text("[vUnstable] ")
            .color(NamedTextColor.DARK_RED)
            .append(Component.text("Required for 5-tick nuke: ")
                .color(NamedTextColor.YELLOW))
            .append(Component.text(String.valueOf(requiredValue))
                .color(NamedTextColor.WHITE)));
        
        // Instructions header
        player.sendMessage(Component.text("[vUnstable] ")
            .color(NamedTextColor.DARK_RED)
            .append(Component.text("Add this to spigot.yml:")
                .color(NamedTextColor.GREEN)));
        
        // YAML config lines
        player.sendMessage(Component.text("world-settings:")
            .color(NamedTextColor.WHITE));
        player.sendMessage(Component.text("  default:")
            .color(NamedTextColor.WHITE));
        player.sendMessage(Component.text("    max-tnt-per-tick: 5000")
            .color(NamedTextColor.WHITE));
        
        // Footer with restart warning and fallback info
        int fallbackTicks = 2000 / 100; // 20 ticks at 100/tick
        Component statusMessage = Component.text("[vUnstable] ")
            .color(NamedTextColor.DARK_RED)
            .append(Component.text("Then restart server. Current fallback: 100/tick (" + fallbackTicks + " ticks)")
                .color(NamedTextColor.YELLOW));
        
        // Add clickable status command if available
        if (player.hasPermission("vunstable.admin")) {
            statusMessage = statusMessage.append(Component.newline())
                .append(Component.text("[vUnstable] ")
                    .color(NamedTextColor.DARK_RED))
                .append(Component.text("Click for status: ")
                    .color(NamedTextColor.GRAY))
                .append(Component.text("[/vu status]")
                    .color(NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.runCommand("/vu status"))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to check optimization status")
                        .color(NamedTextColor.GRAY))));
        }
        
        player.sendMessage(statusMessage);
    }
    
    /**
     * Clears the notified admins set (useful for testing or on reload).
     */
    public void clearNotifiedAdmins() {
        notifiedAdmins.clear();
    }
    
    /**
     * Returns the set of admins who have been notified this session.
     */
    public Set<UUID> getNotifiedAdmins() {
        return new HashSet<>(notifiedAdmins);
    }
}
