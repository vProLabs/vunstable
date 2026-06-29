package com.vprolabs.vunstable.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

/**
 * UpdateChecker - Checks Modrinth API for plugin updates.
 * 
 * Features:
 * - Async HTTP requests (non-blocking)
 * - 1-hour result caching
 * - Graceful error handling
 * - JSON response parsing
 */
public class UpdateChecker {
    
    private static final String MODRINTH_PROJECT_ID = "dhEhlFIx";
    private static final String API_URL = "https://api.modrinth.com/v2/project/" + MODRINTH_PROJECT_ID + "/version";
    private static final String DOWNLOAD_URL = "https://modrinth.com/project/" + MODRINTH_PROJECT_ID;
    private static final long CACHE_DURATION_MS = 3600000; // 1 hour
    
    private final JavaPlugin plugin;
    private final String currentVersion;
    
    private String latestVersion = null;
    private String downloadUrl = DOWNLOAD_URL;
    private boolean updateAvailable = false;
    private long lastCheckTime = 0;
    private boolean checkInProgress = false;
    
    public UpdateChecker(JavaPlugin plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getPluginMeta().getVersion();
    }
    
    /**
     * Performs an async version check against Modrinth API.
     * Returns CompletableFuture that completes when check is done.
     */
    public CompletableFuture<Boolean> checkForUpdates() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        // vAPI does not expose async scheduling; use Bukkit directly for HTTP I/O
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Check if cache is still valid
            long now = System.currentTimeMillis();
            if (latestVersion != null && (now - lastCheckTime) < CACHE_DURATION_MS) {
                plugin.getLogger().info("[vUnstable] Using cached update check result");
                future.complete(updateAvailable);
                return;
            }
            
            // Prevent concurrent checks
            if (checkInProgress) {
                plugin.getLogger().fine("[vUnstable] Update check already in progress");
                future.complete(updateAvailable);
                return;
            }
            
            checkInProgress = true;
            
            try {
                plugin.getLogger().info("[vUnstable] Checking Modrinth for updates...");
                
                HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(API_URL).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "vUnstable-UpdateChecker/" + currentVersion);
                conn.setConnectTimeout(5000); // 5 second timeout
                conn.setReadTimeout(5000);
                conn.setInstanceFollowRedirects(true);
                
                int responseCode = conn.getResponseCode();
                
                if (responseCode == 200) {
                    // Read response
                    StringBuilder response = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                    }
                    
                    // Parse JSON
                    JsonArray versions = JsonParser.parseString(response.toString()).getAsJsonArray();
                    
                    if (versions.size() > 0) {
                        JsonElement latest = versions.get(0);
                        latestVersion = latest.getAsJsonObject().get("version_number").getAsString();
                        
                        // Try to get download URL from latest version
                        if (latest.getAsJsonObject().has("files") && 
                            latest.getAsJsonObject().getAsJsonArray("files").size() > 0) {
                            downloadUrl = latest.getAsJsonObject()
                                .getAsJsonArray("files")
                                .get(0).getAsJsonObject()
                                .get("url").getAsString();
                        }
                        
                        // Compare versions
                        updateAvailable = isNewerVersion(latestVersion, currentVersion);
                        
                        lastCheckTime = System.currentTimeMillis();
                        
                        plugin.getLogger().info("[vUnstable] Update check complete. Latest: " + latestVersion + 
                            " (Current: " + currentVersion + ", Update available: " + updateAvailable + ")");
                        
                        future.complete(updateAvailable);
                        return;
                    } else {
                        plugin.getLogger().warning("[vUnstable] Modrinth API returned empty versions array");
                    }
                } else {
                    plugin.getLogger().warning("[vUnstable] Modrinth API returned HTTP " + responseCode);
                }
                
                conn.disconnect();
                
            } catch (SocketTimeoutException e) {
                plugin.getLogger().warning("[vUnstable] Update check timed out (Modrinth API)");
            } catch (Exception e) {
                plugin.getLogger().warning("[vUnstable] Update check failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                future.completeExceptionally(e);
                return;
            } finally {
                checkInProgress = false;
            }
            
            future.complete(false);
        });
        
        return future;
    }
    
    /**
     * Compares two version strings.
     * Returns true if newVersion is newer than currentVersion.
     */
    private boolean isNewerVersion(String newVersion, String currentVersion) {
        try {
            // Simple string comparison works for semantic versioning (1.1.0 vs 1.1.1)
            // For more complex versions, we'd use a semver library
            return newVersion.compareTo(currentVersion) > 0;
        } catch (Exception e) {
            // Fallback: just check if they're different
            return !newVersion.equals(currentVersion);
        }
    }
    
    /**
     * Forces a fresh update check, bypassing cache.
     */
    public CompletableFuture<Boolean> forceCheck() {
        lastCheckTime = 0;
        latestVersion = null;
        return checkForUpdates();
    }
    
    // ==================== GETTERS ====================
    
    public String getCurrentVersion() {
        return currentVersion;
    }
    
    public String getLatestVersion() {
        return latestVersion;
    }
    
    public String getDownloadUrl() {
        return downloadUrl;
    }
    
    public boolean isUpdateAvailable() {
        return updateAvailable;
    }
    
    public long getLastCheckTime() {
        return lastCheckTime;
    }
}
