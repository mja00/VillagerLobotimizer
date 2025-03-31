package dev.mja00.villagerLobotimizer;

import dev.mja00.villagerLobotimizer.listeners.EntityListener;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

public final class VillagerLobotimizer extends JavaPlugin {
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
        this.debugging = getConfig().getBoolean("debug", false);
        // Plugin startup logic
        getLogger().info("I'm ready to lobotomize your villagers!");
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
    }

    public boolean isChunkDebugging() {
        return this.chunkDebugging;
    }

    public void setChunkDebugging(boolean chunkDebugging) {
        this.chunkDebugging = chunkDebugging;
    }

    public LobotomizeStorage getStorage() {
        return this.storage;
    }
}
