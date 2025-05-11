package dev.mja00.villagerLobotomizer;

import com.google.gson.Gson;
import dev.mja00.villagerLobotomizer.listeners.EntityListener;
import dev.mja00.villagerLobotomizer.objects.Modrinth;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.MultiLineChart;
import org.bstats.charts.SingleLineChart;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class VillagerLobotomizer extends JavaPlugin {
    private boolean debugging = false;
    private boolean chunkDebugging = false;
    private LobotomizeStorage storage;
    static final HttpRequest request = HttpRequest.newBuilder().GET().uri(URI.create("https://api.modrinth.com/v3/project/villagerlobotomy/version")).build();
    static final HttpClient client = HttpClient.newHttpClient();
    static final Gson gson = new Gson();
    public boolean needsUpdate = false;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.checkForUpdates();
        this.storage = new LobotomizeStorage(this);
        this.getServer().getPluginManager().registerEvents(new EntityListener(this), this);
        LobotomizeCommand lobotomizeCommand = new LobotomizeCommand(this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, command -> {
            command.registrar().register(lobotomizeCommand.createCommand("lobotomy"));
        });
        // Set our debugs based on the config
        this.debugging = this.getConfig().getBoolean("debug");
        this.chunkDebugging = this.getConfig().getBoolean("chunk-debug");

        Metrics metrics = new Metrics(this, 25704);

        this.setupMetrics(metrics);

        // Plugin startup logic
        getLogger().info("I'm ready to lobotomize your villagers!");
        if (this.isDebugging()) {
            getLogger().info("Debug mode is enabled. This will print debug messages to the console.");
        }
    }

    private void setupMetrics(Metrics metrics) {
        // Currently bstats' site doesn't support creating multiline charts, so we need to also split this into 3 single line charts
        metrics.addCustomChart(new MultiLineChart("villagers", () -> {
            Map<String, Integer> returnMap = new HashMap<>();
            LobotomizeStorage storage = getStorage();
            int active = storage.getActive().size();
            int inactive = storage.getLobotomized().size();
            int total = active + inactive;
            returnMap.put("active", active);
            returnMap.put("inactive", inactive);
            returnMap.put("total", total);
            return returnMap;
        }));

        metrics.addCustomChart(new SingleLineChart("active_villagers", () -> getStorage().getActive().size()));
        metrics.addCustomChart(new SingleLineChart("inactive_villagers", () -> getStorage().getLobotomized().size()));
        metrics.addCustomChart(new SingleLineChart("total_villagers", () -> getStorage().getActive().size() + getStorage().getLobotomized().size()));
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        getLogger().info("Man guess I'll put my tools away now :(");
        if (this.storage != null) {
            this.storage.flush();
        }
    }

    public boolean isDebugging() {
        return this.debugging;
    }

    public void setDebugging(boolean debugging) {
        this.debugging = debugging;
        // Update the config
        this.getConfig().set("debug", this.debugging);
        this.saveConfig();
    }

    public boolean isChunkDebugging() {
        return this.chunkDebugging;
    }

    public void setChunkDebugging(boolean chunkDebugging) {
        this.chunkDebugging = chunkDebugging;
        // Update the config
        this.getConfig().set("chunk-debug", this.chunkDebugging);
        this.saveConfig();
    }

    public LobotomizeStorage getStorage() {
        return this.storage;
    }

    private void checkForUpdates() {
        // We need to GET the url. It returns an array of objects for each version
        // This'll be scheduled off thread so no need to worry here
        String currentVersion = this.getPluginMeta().getVersion();
        this.getLogger().info("Checking for updates...");
        this.getServer().getScheduler().runTaskAsynchronously(this, () -> {
            String responseBody = null;
            CompletableFuture<HttpResponse<String>> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                responseBody = response.thenApply(HttpResponse::body).get(5, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException e) {
                this.getLogger().warning("Failed to check for updates: " + e.getMessage());
                return;
            }
            if (responseBody == null || responseBody.isEmpty()) {
                this.getLogger().warning("Failed to check for updates: No response from the server");
                return;
            }
            // Parse our response into json
            List<Modrinth.ModrinthVersion> versions =  Modrinth.fromJson(responseBody);
            if (versions == null || versions.isEmpty()) {
                this.getLogger().warning("Failed to check for updates: No versions found");
                return;
            }
            Modrinth.ModrinthVersion latestVersion = versions.getFirst();

            // Both versions will be semver, so we can do a simple compare to see if current is less than latest
            if (currentVersion.compareTo(latestVersion.getVersionNumber()) < 0) {
                this.getLogger().info("A new version of VillagerLobotomizer is available! (" + latestVersion.getVersionNumber() + ")");
                this.getLogger().info("You can download it here: https://modrinth.com/plugin/villagerlobotomy");
                this.needsUpdate = true;
            } else if (currentVersion.compareTo(latestVersion.getVersionNumber()) > 0) {
                this.getLogger().info("Hey! How'd you get this build?");
            } else {
                this.getLogger().info("You are running the latest version of VillagerLobotomizer.");
            }
        });
    }
}
