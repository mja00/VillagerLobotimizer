package dev.mja00.villagerLobotomizer;

import dev.mja00.villagerLobotomizer.listeners.EntityListener;
import dev.mja00.villagerLobotomizer.objects.Modrinth;
import dev.mja00.villagerLobotomizer.utils.ConfigMigrator;
import dev.mja00.villagerLobotomizer.utils.ModrinthClient;
import dev.mja00.villagerLobotomizer.utils.SentryContextProvider;
import dev.mja00.villagerLobotomizer.utils.VersionUtils;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.sentry.Sentry;
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
import org.bukkit.World;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.*;

public class VillagerLobotomizer extends JavaPlugin {
    private boolean debugging = false;
    private boolean chunkDebugging = false;
    private LobotomizeStorage storage;
    private boolean isFolia;
    public boolean needsUpdate = false;
    public Team activeVillagersTeam;
    public Team inactiveVillagersTeam;
    private final String activeVillagersTeamName = "lobotomy_active_villagers";
    private final String inactiveVillagersTeamName = "lobotomy_inactive_villagers";
    private boolean disableChunkVillagerUpdate;
    private boolean sentryEnabled = false;

    /**
     * Initializes the plugin, loading configuration, storage, listeners, commands, and debug features.
     */
    @Override
    public void onEnable() {
        ConfigMigrator migrator = new ConfigMigrator(this);
        migrator.migrateConfig();
        boolean disableUpdateCheck = this.getConfig().getBoolean("disable-update-checker", false);
        if (!disableUpdateCheck) {
            this.checkForUpdates();
        } else {
            this.getLogger().info("Update checker is disabled. You will not be notified of new versions.");
        }
        this.isFolia = this.detectFolia();
        if (this.isFolia) {
            this.getLogger().info("Folia detected, using per-entity schedulers.");
        }

        // Initialize Sentry to match config (also handles enable/disable transitions on reload)
        this.applySentryConfig();

        this.storage = new LobotomizeStorage(this);
        this.getServer().getPluginManager().registerEvents(new EntityListener(this), this);
        LobotomizeCommand lobotomizeCommand = new LobotomizeCommand(this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, command -> {
            command.registrar().register(lobotomizeCommand.createCommand("lobotomy"));
        });
        this.debugging = this.getConfig().getBoolean("debug");
        this.chunkDebugging = this.getConfig().getBoolean("chunk-debug");
        this.disableChunkVillagerUpdate = this.getConfig().getBoolean("disable-chunk-villager-updates");
        boolean createDebuggingTeams = this.getConfig().getBoolean("create-debug-teams", false);

        // Metrics must never take down plugin enable; bStats throws IllegalStateException when running
        // unrelocated (tests) and a LinkageError if shaded wrong. Don't catch fatal Errors like OOM.
        try {
            Metrics metrics = new Metrics(this, 25704);
            this.setupMetrics(metrics);
        } catch (Exception | LinkageError e) {
            this.getLogger().log(java.util.logging.Level.WARNING, "Failed to initialize metrics", e);
        }

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
            case DANGEROUS_FORK:
                this.getLogger().severe("You are running a server fork that is known to be extremely dangerous and lead to data loss. It is strongly recommended you switch to a more stable server software like Paper.");
                break;
            case UNSTABLE:
                this.getLogger().severe("You are running a server that does not properly support Bukkit plugins. Bukkit plugins should not be used with Forge/Fabric mods!");
                break;
            case OUTDATED:
                this.getLogger().severe("You are running an unsupported server version!");
                break;
            case FULL:
                // Fully supported, no warning needed
                break;
        }

        if (VersionUtils.getSupportStatusClass() != null) {
            this.getLogger().info(String.format("Status determining class: %s", VersionUtils.getSupportStatusClass()));
        }

        getLogger().info("I'm ready to lobotomize your villagers!");
        if (this.isDebugging()) {
            getLogger().info("Debug mode is enabled. This will print debug messages to the console.");
            // Active/inactive teams let us toggle glow on villagers in debug mode
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
        if (scoreboardManager == null) {
            this.getLogger().warning("Scoreboard manager is not available. Skipping debug team creation.");
            this.activeVillagersTeam = null;
            this.inactiveVillagersTeam = null;
            return;
        }
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
        getLogger().info("Man guess I'll put my tools away now :(");
        if (this.storage != null) {
            this.storage.flush();
        }
        // No need to cancel tasks manually - Paper handles this automatically on disable
        // Clean up debug teams (non-Folia only)
        if (!this.isFolia) {
            ScoreboardManager scoreboardManager = this.getServer().getScoreboardManager();
            if (scoreboardManager != null) {
                Team activeTeam = scoreboardManager.getMainScoreboard().getTeam(this.activeVillagersTeamName);
                clearTeam(activeTeam);
                Team inactiveTeam = scoreboardManager.getMainScoreboard().getTeam(this.inactiveVillagersTeamName);
                clearTeam(inactiveTeam);
            } else {
                this.getLogger().warning("Scoreboard manager unavailable during shutdown; debug teams cannot be cleaned up.");
            }
        }

        if (this.sentryEnabled) {
            try {
                Sentry.close();
                if (this.isDebugging()) {
                    this.getLogger().info("Sentry shutdown complete");
                }
            } catch (Exception e) {
                this.getLogger().log(java.util.logging.Level.WARNING, "Error during Sentry shutdown: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Removes all villagers from a team, unglows them, and unregisters the team.
     */
    private void clearTeam(Team team) {
        if (team != null) {
            // Unglow entries before unregistering
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

    /**
     * Enables or disables debug mode and updates debug teams if running on a non-Folia server.
     *
     * @param debugging true to enable debug mode, false to disable
     */
    public void setDebugging(boolean debugging) {
        this.debugging = debugging;

        // Skip team operations on Folia
        if (!this.isFolia) {
            if (!debugging) {
                ScoreboardManager scoreboardManager = this.getServer().getScoreboardManager();
                if (scoreboardManager != null) {
                    Team activeTeam = scoreboardManager.getMainScoreboard().getTeam(this.activeVillagersTeamName);
                    clearTeam(activeTeam);
                    Team inactiveTeam = scoreboardManager.getMainScoreboard().getTeam(this.inactiveVillagersTeamName);
                    clearTeam(inactiveTeam);
                } else {
                    this.getLogger().warning("Scoreboard manager is unavailable; skipping debug team cleanup.");
                }
                this.activeVillagersTeam = null;
                this.inactiveVillagersTeam = null;
            } else {
                createDebuggingTeams();
            }
        } else if (debugging) {
            this.getLogger().info("Debug mode enabled. Note: Villager teams/glowing are not available on Folia.");
        }

        this.getConfig().set("debug", this.debugging);
        this.saveConfig();
    }

    public boolean isChunkDebugging() {
        return this.chunkDebugging;
    }

    /**
     * Enables or disables chunk debugging and persists the setting.
     *
     * @param chunkDebugging whether chunk debugging should be enabled
     */
    public void setChunkDebugging(boolean chunkDebugging) {
        this.chunkDebugging = chunkDebugging;
        this.getConfig().set("chunk-debug", this.chunkDebugging);
        this.saveConfig();
    }

    public LobotomizeStorage getStorage() {
        return this.storage;
    }


    /**
     * Checks Modrinth for a newer version of the plugin and flags if an update is available.
     */
    private void checkForUpdates() {
        // Runs off-thread via async scheduler
        String currentVersion = this.getPluginMeta().getVersion();
        this.getLogger().info("Checking for updates...");
        VillagerLobotomizer plugin = this;
        Bukkit.getAsyncScheduler().runNow(this, (task) -> {
            List<Modrinth.Version> versions;
            try {
                versions = ModrinthClient.fetchVersions();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to check for updates: " + e.getMessage());
                return;
            }
            if (versions == null || versions.isEmpty()) {
                plugin.getLogger().warning("Failed to check for updates: No versions found");
                return;
            }
            Modrinth.Version latestVersion = versions.stream()
                    .filter(v -> "release".equalsIgnoreCase(v.version_type()))
                    .findFirst()
                    .orElse(null);
            if (latestVersion == null) {
                plugin.getLogger().warning("Failed to check for updates: No release versions found");
                return;
            }


            int comparison = dev.mja00.villagerLobotomizer.utils.StringUtils.compareSemVer(currentVersion, latestVersion.version_number());
            if (comparison < 0) {
                plugin.getLogger().info("A new version of VillagerLobotimizer is available! (" + latestVersion.version_number() + ")");
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

    /**
     * Checks if Sentry error tracking is enabled.
     *
     * @return {@code true} if Sentry is enabled, {@code false} otherwise
     */
    public boolean isSentryEnabled() {
        return this.sentryEnabled;
    }

    /**
     * Initializes or shuts down Sentry to match the current {@code enable-sentry} configuration value.
     * Transitions safely in both directions and is idempotent.
     */
    private void applySentryConfig() {
        boolean enableSentry = this.getConfig().getBoolean("enable-sentry", true);
        if (enableSentry) {
            // Already initialized (e.g. fresh enable after a soft plugin reload, or a prior reload)
            if (Sentry.isEnabled()) {
                this.sentryEnabled = true;
                this.getLogger().info("Sentry is already initialized, skipping re-initialization");
                return;
            }
            initializeSentry();
        } else {
            // Disabled in config: close it if it was previously running so a reload takes effect.
            if (this.sentryEnabled || Sentry.isEnabled()) {
                try {
                    Sentry.close();
                    if (this.isDebugging()) {
                        this.getLogger().info("Sentry shutdown complete (disabled via config)");
                    }
                } catch (Exception e) {
                    this.getLogger().log(java.util.logging.Level.WARNING, "Error during Sentry shutdown: " + e.getMessage(), e);
                }
            }
            this.sentryEnabled = false;
            this.getLogger().info("Sentry error tracking is disabled in config");
        }
    }

    /**
     * Initializes the Sentry error tracking SDK with project configuration.
     *
     * Configures Sentry with DSN, environment (determined by the {@code villagerlobotimizer.dev}
     * system property), release tag, and server context tags. Sets {@code sentryEnabled} to
     * {@code true} on success, or {@code false} and logs a warning on failure.
     */
    private void initializeSentry() {
        // dev mode set by the runServer task
        boolean isDev = Boolean.getBoolean("villagerlobotimizer.dev");
        String environment = isDev ? "development" : "production";

        // Allow operators to redirect sentry events to their own project (or disable entirely with
        // an empty string) by setting the system property at JVM startup. The default below is the
        // project's official ingestion endpoint; treat it as public by design.
        String sentryDsn = System.getProperty(
                "villagerlobotimizer.sentry.dsn",
                "https://fdd79b92bf9f83a2f9699e844c080019@o1234338.ingest.us.sentry.io/4510592886702080");

        try {
            Sentry.init(options -> {
                options.setDsn(sentryDsn);
                options.setEnvironment(environment);
                options.setRelease("villagerlobotimizer@" + this.getPluginMeta().getVersion());

                try {
                    options.setTag("server.brand", SentryContextProvider.getServerBrand());
                    options.setTag("server.version", SentryContextProvider.getServerVersion());
                    options.setTag("minecraft.version", SentryContextProvider.getMinecraftVersion());
                    options.setTag("bukkit.version", SentryContextProvider.getBukkitVersion());
                    options.setTag("java.version", SentryContextProvider.getJavaVersion());
                    options.setTag("folia.enabled", String.valueOf(this.isFolia));
                } catch (Exception e) {
                    this.getLogger().log(java.util.logging.Level.WARNING, "Failed to set Sentry context tags: " + e.getMessage(), e);
                }

                options.setAttachStacktrace(true);
                options.setAttachThreads(true);

                // Mark our package as in-app for better source highlighting
                options.addInAppInclude("dev.mja00.villagerLobotomizer");

                options.setTracesSampleRate(0.1);

                // Privacy: disable PII
                options.setSendDefaultPii(false);

                options.setEnableUncaughtExceptionHandler(false);
            });
            this.sentryEnabled = true;
            this.getLogger().info("Sentry error tracking enabled (environment: " + environment + ")");
        } catch (Exception e) {
            this.getLogger().log(java.util.logging.Level.WARNING, "Failed to initialize Sentry: " + e.getMessage(), e);
            this.sentryEnabled = false;
        }
    }

    /**
     * Reloads configuration, reinitializes storage, and rescans all worlds for villagers.
     *
     * @return the number of villagers added to storage, or -1 if storage recreation fails
     */
    public int reloadPluginState() {
        this.reloadConfig();
        // Apply enable-sentry transitions on reload (init if newly enabled, close if newly disabled).
        this.applySentryConfig();
        this.debugging = this.getConfig().getBoolean("debug");
        this.chunkDebugging = this.getConfig().getBoolean("chunk-debug");
        this.disableChunkVillagerUpdate = this.getConfig().getBoolean("disable-chunk-villager-updates");
        boolean createDebuggingTeams = this.getConfig().getBoolean("create-debug-teams", false);

        LobotomizeStorage previousStorage = this.storage;
        // On Folia, the world-wide entity scan in the reload block is unsafe (main thread
        // owns no region). Instead, snapshot the old storage's tracked set and re-add those
        // villagers to the new storage. addVillager is now Folia-safe (the entity mutation
        // dispatches via villager.getScheduler()), so the registration is correct.
        java.util.List<org.bukkit.entity.Villager> foliaReloadVillagers = java.util.List.of();
        if (this.isFolia && previousStorage != null) {
            java.util.Set<org.bukkit.entity.Villager> tracked = new java.util.LinkedHashSet<>();
            tracked.addAll(previousStorage.getActive());
            tracked.addAll(previousStorage.getLobotomized());
            foliaReloadVillagers = new java.util.ArrayList<>(tracked);
        }
        LobotomizeStorage newStorage;
        try {
            newStorage = new LobotomizeStorage(this);
        } catch (Exception e) {
            this.getLogger().severe("Failed to reload storage; keeping previous storage instance. Error: " + e.getMessage());
            if (this.isDebugging()) {
                this.getLogger().log(java.util.logging.Level.SEVERE, "Storage reload stack trace", e);
            }
            // Preserve existing storage to avoid leaving it in shutdown state
            return -1;
        }

        if (previousStorage != null) {
            // Reload: plugin stays enabled, so dispatch wake work via the entity scheduler (Folia-safe).
            previousStorage.flush(true);
        }
        this.storage = newStorage;

        // Reset/recreate debug teams per current config
        if (!this.isFolia) {
            ScoreboardManager scoreboardManager = this.getServer().getScoreboardManager();
            if (scoreboardManager != null) {
                Team activeTeam = scoreboardManager.getMainScoreboard().getTeam(this.activeVillagersTeamName);
                Team inactiveTeam = scoreboardManager.getMainScoreboard().getTeam(this.inactiveVillagersTeamName);
                if (!this.debugging || !createDebuggingTeams) {
                    clearTeam(activeTeam);
                    clearTeam(inactiveTeam);
                    this.activeVillagersTeam = null;
                    this.inactiveVillagersTeam = null;
                }
            } else if (this.debugging && createDebuggingTeams) {
                this.getLogger().warning("Scoreboard manager is unavailable; debug teams cannot be recreated.");
            }
        }

        if (this.debugging && createDebuggingTeams) {
            createDebuggingTeams();
        }

        int villagers = 0;
        if (this.isFolia) {
            for (Villager villager : foliaReloadVillagers) {
                this.storage.addVillager(villager);
                villagers++;
            }
            this.getLogger().info("Reload on Folia: re-registered " + villagers
                    + " previously tracked villager(s) via entity schedulers.");
        } else {
            for (World world : Bukkit.getWorlds()) {
                for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                    this.storage.addVillager(villager);
                    villagers++;
                }
            }
        }
        return villagers;
    }
}
