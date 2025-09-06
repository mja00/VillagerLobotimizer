package dev.mja00.villagerLobotomizer;

import com.google.gson.Gson;
import dev.mja00.villagerLobotomizer.listeners.EntityListener;
import dev.mja00.villagerLobotomizer.objects.Modrinth;
import dev.mja00.villagerLobotomizer.utils.VersionUtils;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.MultiLineChart;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class VillagerLobotomizer extends JavaPlugin {
    private boolean debugging = false;
    private boolean chunkDebugging = false;
    private LobotomizeStorage storage;
    private boolean isFolia;
    static final HttpRequest request = HttpRequest.newBuilder().GET().uri(URI.create("https://api.modrinth.com/v3/project/villagerlobotomy/version")).build();
    static final HttpClient client = HttpClient.newHttpClient();
    static final Gson gson = new Gson();
    public boolean needsUpdate = false;
    public Team activeVillagersTeam;
    public Team inactiveVillagersTeam;
    private final String activeVillagersTeamName = "lobotomy_active_villagers";
    private final String inactiveVillagersTeamName = "lobotomy_inactive_villagers";
    private boolean disableChunkVillagerUpdate;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        boolean disableUpdateCheck = this.getConfig().getBoolean("disable-update-checker", false);
        if (!disableUpdateCheck) {
            this.checkForUpdates();
        } else {
            this.getLogger().info("Update checker is disabled. You will not be notified of new versions.");
        }
        // Detect if we're running on Folia
        this.isFolia = this.detectFolia();
        if (this.isFolia) {
            this.getLogger().info("Folia detected, using per-entity schedulers.");
        }
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
        this.disableChunkVillagerUpdate = this.getConfig().getBoolean("disable-chunk-villager-updates");
        boolean createDebuggingTeams = this.getConfig().getBoolean("create-debug-teams", false);

        Metrics metrics = new Metrics(this, 25704);

        this.setupMetrics(metrics);

        // Check to see if plugman (or its fork Plugmanx) is installed. If so send a warning.
        PluginManager pluginManager = this.getServer().getPluginManager();
        for (Plugin plugin : pluginManager.getPlugins()) {
            String pluginName = plugin.getName().toLowerCase();
            String pluginVersion = plugin.getPluginMeta().getVersion();
            if (pluginName.contains("plugman")) {
                int pluginMajor = pluginVersion.split("\\.")[0].matches("\\d+") ? Integer.parseInt(pluginVersion.split("\\.")[0]) : 0;
                if (pluginMajor < 2) {
                    this.getLogger().warning("------------------------------");
                    this.getLogger().warning("Plugman is installed. While this plugin will not fully break with it installed, commands will stop working after a reload.");
                    this.getLogger().warning("This is due to the way commands are registered for Brigadier. Plugman does not support plugins that use Brigadier commands.");
                    this.getLogger().warning("A workaround is running \"/minecraft:reload\" after reloading this plugin, however this may break other plugins.");
                    this.getLogger().warning("-------------------------------");
                } else {
                    // They're running a version that doesn't break Brig but still put a little warning in
                    this.getLogger().warning("Plugman is installed, things may act weirdly. If you experience any issues, try restarting your server entirely.");
                }
                break;
            }
        }

        switch (VersionUtils.getServerSupportStatus()) {
            case NMS_CLEANROOM:
                this.getLogger().severe("You are running a server that does not properly support Bukkit plugins that rely on internal Mojang code.");
                break;
            case DANGEROUS_FORK:
                this.getLogger().severe("You are running a server fork that is known to be extremely dangerous and lead to data loss. It is strongly recommended you switch to a more stable server software like Paper.");
                break;
            case STUPID_PLUGIN:
                this.getLogger().severe("You are using plugins known to cause severe issues with VillagerLobotomy and other plugins.");
                break;
            case UNSTABLE:
                this.getLogger().severe("You are running a server that does not properly support Bukkit plugins. Bukkit plugins should not be used with Forge/Fabric mods!");
                break;
            case OUTDATED:
                this.getLogger().severe("You are running an unsupported server version!");
                break;
            case LIMITED:
                this.getLogger().info("You are running a server with limited API functionality. Some features may become unavailable.");
                break;
        }

        if (VersionUtils.getSupportStatusClass() != null) {
            this.getLogger().info(String.format("Status determining class: %s", VersionUtils.getSupportStatusClass()));
        }

        // Plugin startup logic
        getLogger().info("I'm ready to lobotomize your villagers!");
        if (this.isDebugging()) {
            getLogger().info("Debug mode is enabled. This will print debug messages to the console.");
            // Ensure two teams are created for debugging purposes, active and inactive villagers
            // Colors for the teams are green and red respectively
            // This way we can toggle glow on them in debug mode
            if (createDebuggingTeams) {
                createDebuggingTeams();
            }
        }
    }

    private void createDebuggingTeams() {
        // Folia doesn't support scoreboard teams (global state that can't be regionized)
        if (this.isFolia) {
            this.getLogger().warning("Debug teams are not supported on Folia - scoreboard operations are not available.");
            this.getLogger().warning("Debug mode will still work, but villagers will not glow or be added to teams.");
            return;
        }

        ScoreboardManager scoreboardManager = this.getServer().getScoreboardManager();
        Team activeTeam = scoreboardManager.getMainScoreboard().getTeam(this.activeVillagersTeamName);
        if (activeTeam == null) {
            activeTeam = scoreboardManager.getMainScoreboard().registerNewTeam(this.activeVillagersTeamName);
            activeTeam.displayName(Component.text("Active Villagers"));
            activeTeam.color(NamedTextColor.GREEN);
        }
        Team inactiveTeam = scoreboardManager.getMainScoreboard().getTeam(this.inactiveVillagersTeamName);
        if (inactiveTeam == null) {
            inactiveTeam = scoreboardManager.getMainScoreboard().registerNewTeam(this.inactiveVillagersTeamName);
            inactiveTeam.displayName(Component.text("Inactive Villagers"));
            inactiveTeam.color(NamedTextColor.RED);
        }
        this.activeVillagersTeam = activeTeam;
        this.inactiveVillagersTeam = inactiveTeam;
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
        metrics.addCustomChart(new SimplePie("is_folia", () -> this.isFolia ? "yes" : "no"));
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        getLogger().info("Man guess I'll put my tools away now :(");
        if (this.storage != null) {
            this.storage.flush();
        }
        // No need to cancel tasks manually - Paper handles this automatically on disable
        // If either of the teams are not null, we need to remove them (only on non-Folia servers)
        if (!this.isFolia) {
            Team activeTeam = this.getServer().getScoreboardManager().getMainScoreboard().getTeam(this.activeVillagersTeamName);
            clearTeam(activeTeam);
            Team inactiveTeam = this.getServer().getScoreboardManager().getMainScoreboard().getTeam(this.inactiveVillagersTeamName);
            clearTeam(inactiveTeam);
        }
    }

    private void clearTeam(Team team) {
        if (team != null) {
            // Get all entities on the team and unglow them just in case
            for (String entry : team.getEntries()) {
                UUID uuid = UUID.fromString(entry);
                Entity entity = this.getServer().getEntity(uuid);
                if (entity instanceof Villager) {
                    entity.setGlowing(false);
                    team.removeEntity(entity);
                }
            }
            try {
                team.unregister();
            } catch (IllegalStateException e) {
                // This can happen if the team is already unregistered, we can safely ignore this
                this.getLogger().warning("Failed to unregister team " + team.getName() + ": " + e.getMessage());
            }
        }
    }

    public boolean isDebugging() {
        return this.debugging;
    }

    public void setDebugging(boolean debugging) {
        this.debugging = debugging;

        // Skip team operations on Folia
        if (!this.isFolia) {
            // If debugging is being set to false, we need to clean up the teams
            if (!debugging) {
                Team activeTeam = this.getServer().getScoreboardManager().getMainScoreboard().getTeam(this.activeVillagersTeamName);
                clearTeam(activeTeam);
                Team inactiveTeam = this.getServer().getScoreboardManager().getMainScoreboard().getTeam(this.inactiveVillagersTeamName);
                clearTeam(inactiveTeam);
            } else {
                // Create them again
                createDebuggingTeams();
            }
        } else if (debugging) {
            this.getLogger().info("Debug mode enabled. Note: Villager teams/glowing are not available on Folia.");
        }

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
        VillagerLobotomizer plugin = this;
        Bukkit.getAsyncScheduler().runNow(this, (task) -> {
            String responseBody = null;
            CompletableFuture<HttpResponse<String>> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                responseBody = response.thenApply(HttpResponse::body).get(5, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException e) {
                plugin.getLogger().warning("Failed to check for updates: " + e.getMessage());
                return;
            }
            if (responseBody == null || responseBody.isEmpty()) {
                plugin.getLogger().warning("Failed to check for updates: No response from the server");
                return;
            }
            // Parse our response into json and filter to only release versions (ignore beta/alpha)
            List<Modrinth.ModrinthVersion> versions = Modrinth.fromJson(responseBody);
            if (versions == null || versions.isEmpty()) {
                plugin.getLogger().warning("Failed to check for updates: No versions found");
                return;
            }
            // Only consider versions where version_type is "release"
            Modrinth.ModrinthVersion latestVersion = versions.stream()
                    .filter(v -> "release".equalsIgnoreCase(v.getVersionType()))
                    .findFirst()
                    .orElse(null);
            if (latestVersion == null) {
                plugin.getLogger().warning("Failed to check for updates: No release versions found");
                return;
            }


            // Compare versions using proper semantic versioning
            int comparison = dev.mja00.villagerLobotomizer.utils.StringUtils.compareSemVer(currentVersion, latestVersion.getVersionNumber());
            if (comparison < 0) {
                plugin.getLogger().info("A new version of VillagerLobotomizer is available! (" + latestVersion.getVersionNumber() + ")");
                plugin.getLogger().info("You can download it here: https://modrinth.com/plugin/villagerlobotomy");
                plugin.needsUpdate = true;
            } else if (comparison > 0) {
                plugin.getLogger().info("Hey! How'd you get this build?");
            } else {
                plugin.getLogger().info("You are running the latest version of VillagerLobotomizer.");
            }
        });
    }

    public Team getActiveVillagersTeam() {
        return this.activeVillagersTeam;
    }

    public Team getInactiveVillagersTeam() {
        return this.inactiveVillagersTeam;
    }

    public boolean isFolia() {
        return this.isFolia;
    }

    /**
     * Detects if the server is running Folia
     */
    private boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public boolean isDisableChunkVillagerUpdate() {
        return this.disableChunkVillagerUpdate;
    }
}
