package com.vprolabs.vunstable.command;

import com.vprolabs.vunstable.config.SpigotOptimizer;
import com.vprolabs.vunstable.rod.RodManager;
import com.vprolabs.vunstable.util.UpdateChecker;
import com.vprolabs.vunstable.vUnstable;
import net.kyori.adventure.text.Component;
import xyz.vprolabs.vapi.VAPI;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RodCommand - Handles /vunstable and /rod commands with comprehensive validation.
 * 
 * Usage: /vunstable give <nuke|stab> [player]
 * 
 * Features:
 * - Null-safe argument validation
 * - Exact player name matching (no partial matches)
 * - Console-safe execution
 * - Inventory full detection with item drop
 * - Anti-spam cooldown system
 * - Color-coded error messages
 */
public class RodCommand implements TabExecutor {
    
    private final JavaPlugin plugin;
    private final RodManager rodManager;
    private static final List<String> ROD_TYPES = Arrays.asList("nuke", "stab");
    private static final String PERMISSION_USE = "vunstable.use";
    private static final String PERMISSION_GIVE = "vunstable.give";
    private static final String PERMISSION_GIVE_OTHERS = "vunstable.give.others";
    private static final String PERMISSION_RELOAD = "vunstable.reload";
    private static final String PERMISSION_ADMIN = "vunstable.admin";
    
    // Cooldown map: Player UUID -> Last give time in milliseconds
    private final Map<UUID, Long> lastGiveTime = new HashMap<>();
    private static final long COOLDOWN_MS = 1000; // 1 second cooldown
    
    public RodCommand(JavaPlugin plugin) {
        this.plugin = plugin;
        this.rodManager = ((vUnstable) plugin).getRodManager();
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Debug log
        plugin.getLogger().fine("[vUnstable] Command executed: " + command.getName() + " args: " + Arrays.toString(args));
        
        // Check base permission
        if (!sender.hasPermission(PERMISSION_USE)) {
            sendError(sender, "You don't have permission to use this command!");
            return true;
        }
        
        // Check args length - show help if no args
        if (args == null || args.length == 0) {
            sendHelp(sender);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        // Route to appropriate handler
        switch (subCommand) {
            case "give":
                return handleGive(sender, args);
            case "reload":
                return handleReload(sender);
            case "status":
                return handleStatus(sender);
            case "update":
                return handleUpdate(sender);
            default:
                sendError(sender, "Unknown command. Usage: /vu give <nuke|stab> [player]");
                return true;
        }
    }
    
    private boolean handleGive(CommandSender sender, String[] args) {
        // Check specific permission for giving rods
        if (!sender.hasPermission(PERMISSION_GIVE)) {
            sendError(sender, "You don't have permission to give rods!");
            return true;
        }
        
        // ==================== ARGUMENT VALIDATION ====================
        
        // Check minimum args: /vu give <rod>
        if (args.length < 2) {
            sendError(sender, "Usage: /vu give <nuke|stab> [player]");
            return true;
        }
        
        // Validate rod type
        String rodType = args[1].toLowerCase();
        if (!ROD_TYPES.contains(rodType)) {
            sendError(sender, "Unknown rod type. Use: nuke, stab");
            return true;
        }
        
        // ==================== PLAYER RESOLUTION LAYER ====================
        
        Player target;
        
        if (args.length >= 3) {
            // Target specified: /vu give <rod> <player>
            String targetName = args[2];
            
            // Null check on target name
            if (targetName == null || targetName.trim().isEmpty()) {
                sendError(sender, "Please specify a player");
                return true;
            }
            
            // Use getPlayerExact for exact name matching (prevents partial matches)
            target = Bukkit.getPlayerExact(targetName);
            
            // Online check
            if (target == null || !target.isOnline()) {
                sendError(sender, "Player '" + targetName + "' is not online");
                return true;
            }
            
        } else {
            // No target specified - use sender
            if (sender instanceof ConsoleCommandSender) {
                sendError(sender, "Console must specify player: /vu give <rod> <player>");
                return true;
            }
            
            if (!(sender instanceof Player)) {
                sendError(sender, "You must be a player to use this command");
                return true;
            }
            
            target = (Player) sender;
        }
        
        // ==================== PERMISSION LAYER ====================
        
        boolean givingToSelf = sender.equals(target);
        
        if (!givingToSelf) {
            // Giving to another player requires vunstable.give.others
            if (!sender.hasPermission(PERMISSION_GIVE_OTHERS)) {
                sendError(sender, "You don't have permission to give rods to other players");
                return true;
            }
        }
        
        // ==================== COOLDOWN CHECK ====================
        
        if (sender instanceof Player) {
            Player senderPlayer = (Player) sender;
            UUID senderId = senderPlayer.getUniqueId();
            long now = System.currentTimeMillis();
            
            if (lastGiveTime.containsKey(senderId)) {
                long lastTime = lastGiveTime.get(senderId);
                if (now - lastTime < COOLDOWN_MS) {
                    long remaining = (COOLDOWN_MS - (now - lastTime)) / 1000 + 1;
                    sendError(sender, "Please wait " + remaining + " second(s) before using this again");
                    return true;
                }
            }
            
            lastGiveTime.put(senderId, now);
        }
        
        // ==================== SAFETY CHECKS ====================
        
        // Verify target inventory is accessible
        if (target.getInventory() == null) {
            sendError(sender, "Cannot access player's inventory");
            return true;
        }
        
        // ==================== EXECUTION LAYER ====================
        
        ItemStack rod = rodManager.createRod(rodType);
        if (rod == null) {
            sendError(sender, "Failed to create rod item");
            return true;
        }
        
        // Check if inventory is full
        boolean hasSpace = target.getInventory().firstEmpty() != -1;
        
        if (hasSpace) {
            // Add to inventory
            target.getInventory().addItem(rod);
        } else {
            // Drop at feet
            target.getWorld().dropItemNaturally(target.getLocation(), rod);
            sendWarning(target, "Your inventory was full! Rod dropped at your feet.");
        }
        
        // Play sound to target
        try {
            target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.0f);
        } catch (Exception ignored) {
            // Sound might not exist in some versions, ignore
        }
        
        // ==================== FEEDBACK LAYER ====================
        
        // Success message to sender
        sendSuccess(sender, "Given " + rodType + " rod to " + target.getName());
        
        // Message to target (only if different from sender)
        if (!givingToSelf) {
            target.sendMessage(Component.text("You received the ")
                .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .append(Component.text(rodType + " rod")
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED))
                .append(Component.text("!"))
                .color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
            
            // Log admin action to console
            if (sender instanceof Player) {
                plugin.getLogger().info("[vUnstable] Admin " + sender.getName() + " gave " + rodType + " rod to " + target.getName());
            } else {
                plugin.getLogger().info("[vUnstable] Console gave " + rodType + " rod to " + target.getName());
            }
        }
        
        return true;
    }
    
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission(PERMISSION_RELOAD)) {
            sendError(sender, "Insufficient permissions");
            return true;
        }
        
        try {
            plugin.reloadConfig();
            com.vprolabs.vunstable.config.ConfigManager.getInstance().reload();
            sendSuccess(sender, "Configuration reloaded");
            plugin.getLogger().info("[vUnstable] Configuration reloaded by " + sender.getName());
        } catch (Exception e) {
            sendError(sender, "Failed to reload configuration: " + e.getMessage());
            plugin.getLogger().severe("[vUnstable] Reload failed: " + e.getMessage());
        }
        
        return true;
    }
    
    private boolean handleStatus(CommandSender sender) {
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            sendError(sender, "Insufficient permissions");
            return true;
        }
        
        SpigotOptimizer optimizer = ((vUnstable) plugin).getOptimizer();
        var params = vUnstable.getNukeParams();
        
        sender.sendMessage(Component.text("========== vUnstable Status ==========")
            .color(net.kyori.adventure.text.format.NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD));
        
        // Optimization status
        boolean isOptimized = optimizer.isOptimized();
        sender.sendMessage(Component.text("Optimization: ")
            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
            .append(isOptimized 
                ? Component.text("OPTIMIZED ✓").color(net.kyori.adventure.text.format.NamedTextColor.GREEN)
                : Component.text("NOT OPTIMIZED ✗").color(net.kyori.adventure.text.format.NamedTextColor.RED)));
        
        // Current max-tnt-per-tick
        sender.sendMessage(Component.text("Current max-tnt-per-tick: ")
            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(optimizer.getCurrentMaxTntPerTick()))
                .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW)));
        
        // Required for 5-tick nuke
        sender.sendMessage(Component.text("Required for 5-tick nuke: ")
            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(SpigotOptimizer.TARGET_RATE))
                .color(net.kyori.adventure.text.format.NamedTextColor.GREEN)));
        
        // Current spawn plan
        sender.sendMessage(Component.text("Current spawn plan: ")
            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
            .append(Component.text(params.ratePerTick() + " TNT/tick")
                .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW))
            .append(Component.text(" (" + params.totalTicks() + " ticks total)")
                .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)));
        
        // spigot.yml location
        if (optimizer.isSpigotYmlFound()) {
            sender.sendMessage(Component.text("spigot.yml location: ")
                .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .append(Component.text(optimizer.getSpigotYmlPath())
                    .color(net.kyori.adventure.text.format.NamedTextColor.GREEN)));
        } else {
            sender.sendMessage(Component.text("spigot.yml location: ")
                .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .append(Component.text("NOT FOUND")
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED)));
        }
        
        sender.sendMessage(Component.text("=====================================")
            .color(net.kyori.adventure.text.format.NamedTextColor.GOLD));
        
        return true;
    }
    
    private boolean handleUpdate(CommandSender sender) {
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            sendError(sender, "Insufficient permissions");
            return true;
        }
        
        sendSuccess(sender, "Checking for updates...");
        
        UpdateChecker checker = ((vUnstable) plugin).getUpdateChecker();
        
        checker.forceCheck().thenAccept(updateAvailable -> {
            // Run on main thread for Bukkit API access
            VAPI.getInstance().getScheduler().run(() -> {
                if (updateAvailable) {
                    String current = checker.getCurrentVersion();
                    String latest = checker.getLatestVersion();
                    String url = checker.getDownloadUrl();
                    
                    sender.sendMessage(Component.text("[vUnstable] ")
                        .color(NamedTextColor.DARK_RED)
                        .append(Component.text("Update available! ")
                            .color(NamedTextColor.YELLOW))
                        .append(Component.text("Current: " + current + " ")
                            .color(NamedTextColor.WHITE))
                        .append(Component.text("-> ")
                            .color(NamedTextColor.GRAY))
                        .append(Component.text("Latest: " + latest)
                            .color(NamedTextColor.GREEN)
                            .decorate(TextDecoration.BOLD)));
                    
                    sender.sendMessage(Component.text("[vUnstable] ")
                        .color(NamedTextColor.DARK_RED)
                        .append(Component.text("Download: ")
                            .color(NamedTextColor.YELLOW))
                        .append(Component.text(url)
                            .color(NamedTextColor.AQUA)
                            .decorate(TextDecoration.UNDERLINED)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                Component.text("Click to open download page").color(NamedTextColor.GRAY)))));
                } else {
                    String current = checker.getCurrentVersion();
                    sender.sendMessage(Component.text("[vUnstable] ")
                        .color(NamedTextColor.GREEN)
                        .append(Component.text("You are running the latest version (" + current + ")")
                            .color(NamedTextColor.WHITE)));
                }
            });
        }).exceptionally(ex -> {
            VAPI.getInstance().getScheduler().run(() -> {
                sendError(sender, "Update check failed: " + ex.getMessage());
            });
            return null;
        });
        
        return true;
    }
    
    // ==================== MESSAGE HELPERS ====================
    
    private void sendError(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[vUnstable] " + message)
            .color(net.kyori.adventure.text.format.NamedTextColor.RED));
    }
    
    private void sendSuccess(CommandSender sender, String message) {
        sender.sendMessage(Component.text("[vUnstable] " + message)
            .color(net.kyori.adventure.text.format.NamedTextColor.GREEN));
    }
    
    private void sendWarning(Player player, String message) {
        player.sendMessage(Component.text("[vUnstable] " + message)
            .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW));
    }
    
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("========== vUnstable v" + plugin.getPluginMeta().getVersion() + " ==========")
            .color(NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD));
        sender.sendMessage(Component.text("/vu give <nuke|stab> [player]").color(NamedTextColor.YELLOW)
            .append(Component.text(" - Give destruction rods").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/vu update").color(NamedTextColor.YELLOW)
            .append(Component.text(" - Check for plugin updates").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/vu status").color(NamedTextColor.YELLOW)
            .append(Component.text(" - Check optimization status").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/vu reload").color(NamedTextColor.YELLOW)
            .append(Component.text(" - Reload configuration").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("=====================================")
            .color(NamedTextColor.GOLD));
    }
    
    // ==================== TAB COMPLETION ====================
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (!sender.hasPermission(PERMISSION_USE)) {
            return completions;
        }
        
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            if ("give".startsWith(partial)) {
                completions.add("give");
            }
            if ("reload".startsWith(partial) && sender.hasPermission(PERMISSION_RELOAD)) {
                completions.add("reload");
            }
            if ("status".startsWith(partial) && sender.hasPermission(PERMISSION_ADMIN)) {
                completions.add("status");
            }
            if ("update".startsWith(partial) && sender.hasPermission(PERMISSION_ADMIN)) {
                completions.add("update");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String partial = args[1].toLowerCase();
            completions.addAll(ROD_TYPES.stream()
                .filter(type -> type.startsWith(partial))
                .collect(Collectors.toList()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            // Only show player names if sender has permission to give to others
            if (!sender.hasPermission(PERMISSION_GIVE_OTHERS)) {
                return completions;
            }
            
            String partial = args[2].toLowerCase();
            completions.addAll(Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(partial))
                .collect(Collectors.toList()));
        }
        
        return completions;
    }
}
