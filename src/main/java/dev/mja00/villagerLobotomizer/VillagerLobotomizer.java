package dev.mja00.villagerLobotomizer;

import dev.mja00.villagerLobotomizer.listeners.EntityListener;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.MultiLineChart;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

public final class VillagerLobotomizer extends JavaPlugin {
    private boolean debugging = false;
    private boolean chunkDebugging = false;
    private LobotomizeStorage storage;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
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

        // Plugin startup logic
        getLogger().info("I'm ready to lobotomize your villagers!");
        if (this.isDebugging()) {
            getLogger().info("Debug mode is enabled. This will print debug messages to the console.");
        }
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
}
