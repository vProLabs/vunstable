package com.vprolabs.vunstable.listener;

import com.vprolabs.vunstable.util.ErrorHandler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import xyz.vprolabs.vapi.VAPI;
 
 import java.util.List;

/**
 * AdminErrorNotifier - Notifies admins of errors when they join.
 * 
 * v1.2.0: Notifies admins with vunstable.admin permission about recent errors.
 */
public class AdminErrorNotifier implements Listener {
    
    private final JavaPlugin plugin;
    
    public AdminErrorNotifier(JavaPlugin plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Only notify admins
        if (!player.hasPermission("vunstable.admin")) {
            return;
        }
        
        ErrorHandler errorHandler = ErrorHandler.getInstance();
        if (errorHandler == null || !errorHandler.hasErrors()) {
            return;
        }
        
        // Get errors and notify
        List<ErrorHandler.ErrorEntry> errors = errorHandler.getRecentErrors();
        int errorCount = errors.size();
        
        // Send notification after a short delay to ensure player is fully joined
        VAPI.getInstance().getScheduler().runLater(() -> {
            if (!player.isOnline()) return;
            
            // Header
            player.sendMessage(Component.text("")
                .append(Component.text("[vUnstable] ").color(NamedTextColor.DARK_RED))
                .append(Component.text("⚠ " + errorCount + " error(s) occurred since last check").color(NamedTextColor.RED)));
            
            // Show each error
            for (int i = 0; i < Math.min(errors.size(), 3); i++) {
                ErrorHandler.ErrorEntry entry = errors.get(i);
                String errorType = entry.error.getClass().getSimpleName();
                String context = entry.context;
                
                player.sendMessage(Component.text("  ")
                    .append(Component.text("• ").color(NamedTextColor.RED))
                    .append(Component.text(errorType).color(NamedTextColor.YELLOW))
                    .append(Component.text(" in ").color(NamedTextColor.GRAY))
                    .append(Component.text(context).color(NamedTextColor.WHITE)));
            }
            
            if (errors.size() > 3) {
                player.sendMessage(Component.text("  ")
                    .append(Component.text("• ... and " + (errors.size() - 3) + " more").color(NamedTextColor.GRAY)));
            }
            
            // Support message with clickable Discord link
            Component supportMsg = Component.text("[vUnstable] ").color(NamedTextColor.DARK_RED)
                .append(Component.text("Need help? Contact us: ").color(NamedTextColor.GRAY))
                .append(Component.text("discord.gg/SNzUYWbc5Q")
                    .color(NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl("https://discord.gg/SNzUYWbc5Q"))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to join our Discord").color(NamedTextColor.GRAY))));
            
            player.sendMessage(supportMsg);
            
            // Clear errors after notifying
            errorHandler.clearRecentErrors();
            
        }, 20L); // 1 second delay
    }
}
