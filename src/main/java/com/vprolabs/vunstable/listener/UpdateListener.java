package com.vprolabs.vunstable.listener;

import com.vprolabs.vunstable.util.UpdateChecker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
 * UpdateListener - Notifies admins on join about available updates.
 * 
 * Checks UpdateChecker for update availability and displays
 * formatted messages with download links.
 */
public class UpdateListener implements Listener {
    
    private final JavaPlugin plugin;
    private final UpdateChecker updateChecker;
    private final Set<UUID> notifiedPlayers = new HashSet<>();
    
    private static final String PERMISSION_ADMIN = "vunstable.admin";
    private static final long DELAY_TICKS = 60; // 3 seconds
    
    public UpdateListener(JavaPlugin plugin, UpdateChecker updateChecker) {
        this.plugin = plugin;
        this.updateChecker = updateChecker;
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Check if player is admin
        if (!isAdmin(player)) {
            return;
        }
        
        // Check if update is available
        if (!updateChecker.isUpdateAvailable()) {
            return;
        }
        
        // Check if already notified this session
        if (notifiedPlayers.contains(player.getUniqueId())) {
            return;
        }
        
        // Log to console
        plugin.getLogger().info("[vUnstable] Admin '" + player.getName() + "' joined - notifying of available update");
        
        // Delay notification
        VAPI.getInstance().getScheduler().runLater(() -> {
            if (!player.isOnline()) {
                return;
            }
            
            notifiedPlayers.add(player.getUniqueId());
            sendUpdateNotification(player);
            
            // Play sound
            try {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
            } catch (Exception ignored) {}
        }, DELAY_TICKS);
    }
    
    private boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission(PERMISSION_ADMIN);
    }
    
    private void sendUpdateNotification(Player player) {
        String currentVersion = updateChecker.getCurrentVersion();
        String latestVersion = updateChecker.getLatestVersion();
        String downloadUrl = updateChecker.getDownloadUrl();
        
        // Header
        player.sendMessage(Component.text("[vUnstable] ")
            .color(NamedTextColor.DARK_RED)
            .append(Component.text("Update available! ")
                .color(NamedTextColor.YELLOW))
            .append(Component.text("Current: " + currentVersion + " ")
                .color(NamedTextColor.WHITE))
            .append(Component.text("-> ")
                .color(NamedTextColor.GRAY))
            .append(Component.text("Latest: " + latestVersion)
                .color(NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)));
        
        // Download link
        player.sendMessage(Component.text("[vUnstable] ")
            .color(NamedTextColor.DARK_RED)
            .append(Component.text("Download: ")
                .color(NamedTextColor.YELLOW))
            .append(Component.text(downloadUrl)
                .color(NamedTextColor.AQUA)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(downloadUrl))
                .hoverEvent(HoverEvent.showText(Component.text("Click to open download page")
                    .color(NamedTextColor.GRAY)))));
        
        // Command hint
        player.sendMessage(Component.text("[vUnstable] ")
            .color(NamedTextColor.DARK_RED)
            .append(Component.text("(Or use ")
                .color(NamedTextColor.GRAY))
            .append(Component.text("/vu update")
                .color(NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/vu update"))
                .hoverEvent(HoverEvent.showText(Component.text("Click to check for updates")
                    .color(NamedTextColor.GRAY))))
            .append(Component.text(" to check manually)")
                .color(NamedTextColor.GRAY)));
    }
    
    /**
     * Clears the notified players set (useful for testing).
     */
    public void clearNotifiedPlayers() {
        notifiedPlayers.clear();
    }
    
    public Set<UUID> getNotifiedPlayers() {
        return new HashSet<>(notifiedPlayers);
    }
}
