package dev.mja00.villagerLobotomizer.utils;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;
import java.util.logging.Logger;

public class ConfigMigrator {
    private final JavaPlugin plugin;
    private final Logger logger;
    private static final int CURRENT_CONFIG_VERSION = 1; // Increment whenever you update the config.yml

    public ConfigMigrator(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void migrateConfig() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            logger.severe("Failed to create plugin data folder: " + dataFolder.getAbsolutePath());
            return;
        }

        File configFile = new File(dataFolder, "config.yml");

        if (!configFile.exists()) {
            logger.info("No existing config found, creating default config.");
            plugin.saveDefaultConfig();
            return;
        }

        FileConfiguration existingConfig = YamlConfiguration.loadConfiguration(configFile);
        int existingVersion = existingConfig.getInt("config-version", 0);

        if (existingVersion == CURRENT_CONFIG_VERSION) {
            logger.fine("Config is up to date (version " + CURRENT_CONFIG_VERSION + ")");
            return;
        }

        logger.info("Config migration needed: current version " + existingVersion +
                   " -> target version " + CURRENT_CONFIG_VERSION);

        try {
            createBackup(configFile);

            // Use comment-preserving migration
            String existingYaml = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            String migratedYaml = getString(existingYaml);

            Files.writeString(configFile.toPath(), migratedYaml, StandardCharsets.UTF_8);

            plugin.reloadConfig();
            logger.info("Config migration with comment preservation completed successfully!");
        } catch (IOException e) {
            logger.severe("Failed to migrate config due to I/O error:");
            logger.severe("  Error: " + e.getMessage());
            logger.severe("  Config file: " + configFile.getAbsolutePath());
            logger.severe("  Check file permissions and disk space");
            logger.severe("  Falling back to standard config loading");
            e.printStackTrace();
        } catch (Exception e) {
            logger.severe("Failed to migrate config due to unexpected error:");
            logger.severe("  Error type: " + e.getClass().getSimpleName());
            logger.severe("  Error message: " + e.getMessage());
            logger.severe("  Config file: " + configFile.getAbsolutePath());
            logger.severe("  Existing version: " + existingVersion + " -> Target version: " + CURRENT_CONFIG_VERSION);
            logger.severe("  Please report this error with the stack trace below:");
            e.printStackTrace();

            // Attempt fallback to standard config
            logger.info("Attempting fallback to standard config merge...");
            try {
                fallbackMigration(configFile, existingConfig);
                logger.info("Fallback migration completed successfully");
            } catch (Exception fallbackError) {
                logger.severe("Fallback migration also failed: " + fallbackError.getMessage());
                logger.severe("Config migration aborted - manual intervention required");
            }
        }
    }

    private @NotNull String getString(String existingYaml) throws IOException {
        String defaultYaml = getDefaultConfigAsString();

        CommentPreservingYamlMigrator commentMigrator = new CommentPreservingYamlMigrator(logger);
        String migratedYaml = commentMigrator.mergeWithComments(existingYaml, defaultYaml);

        // Ensure config-version is set
        if (!migratedYaml.contains("config-version:")) {
            migratedYaml = "config-version: " + CURRENT_CONFIG_VERSION + "\n" + migratedYaml;
        } else {
            // Update existing config-version
            migratedYaml = migratedYaml.replaceFirst("config-version:\\s*\\d+", "config-version: " + CURRENT_CONFIG_VERSION);
        }
        return migratedYaml;
    }

    private void createBackup(File configFile) throws IOException {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        File backupFile = new File(plugin.getDataFolder(), "config_backup_" + timestamp + ".yml");
        Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        logger.info("Created config backup: " + backupFile.getName());
    }

    private FileConfiguration getDefaultConfig() throws IOException {
        var resource = plugin.getResource("config.yml");
        if (resource == null) {
            throw new IOException("Default config.yml resource not found in plugin jar");
        }
        return YamlConfiguration.loadConfiguration(new InputStreamReader(resource));
    }

    private String getDefaultConfigAsString() throws IOException {
        var resource = plugin.getResource("config.yml");
        if (resource == null) {
            throw new IOException("Default config.yml resource not found in plugin jar");
        }
        return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }

    private FileConfiguration mergeConfigs(FileConfiguration existing, FileConfiguration defaults) {
        FileConfiguration merged = new YamlConfiguration();

        addMissingKeys(existing, defaults, merged);
        removeObsoleteKeys(existing, defaults, merged);

        return merged;
    }

    private void addMissingKeys(FileConfiguration existing, FileConfiguration defaults, FileConfiguration merged) {
        Set<String> defaultKeys = defaults.getKeys(true);
        Set<String> existingKeys = existing.getKeys(true);

        for (String key : defaultKeys) {
            if (!defaults.isConfigurationSection(key)) {
                if (existingKeys.contains(key)) {
                    merged.set(key, existing.get(key));
                    logger.fine("Preserved existing value for: " + key);
                } else {
                    merged.set(key, defaults.get(key));
                    logger.info("Added new config option: " + key + " = " + defaults.get(key));
                }
            }
        }

        for (String key : defaultKeys) {
            if (defaults.isConfigurationSection(key)) {
                ConfigurationSection defaultSection = defaults.getConfigurationSection(key);
                ConfigurationSection existingSection = existing.getConfigurationSection(key);
                ConfigurationSection mergedSection = merged.createSection(key);

                if (existingSection != null) {
                    mergeSection(existingSection, defaultSection, mergedSection);
                } else {
                    copySection(defaultSection, mergedSection);
                    logger.info("Added new config section: " + key);
                }
            }
        }
    }

    private void removeObsoleteKeys(FileConfiguration existing, FileConfiguration defaults, FileConfiguration merged) {
        Set<String> existingKeys = existing.getKeys(true);
        Set<String> defaultKeys = defaults.getKeys(true);

        for (String key : existingKeys) {
            if (!existing.isConfigurationSection(key) && !defaultKeys.contains(key)) {
                logger.info("Removed obsolete config option: " + key + " (was: " + existing.get(key) + ")");
            }
        }
    }

    private void mergeSection(ConfigurationSection existing, ConfigurationSection defaults, ConfigurationSection merged) {
        if (defaults == null) return;

        for (String key : defaults.getKeys(false)) {
            if (defaults.isConfigurationSection(key)) {
                ConfigurationSection defaultSubSection = defaults.getConfigurationSection(key);
                ConfigurationSection existingSubSection = existing != null ? existing.getConfigurationSection(key) : null;
                ConfigurationSection mergedSubSection = merged.createSection(key);

                if (existingSubSection != null && defaultSubSection != null) {
                    mergeSection(existingSubSection, defaultSubSection, mergedSubSection);
                } else if (defaultSubSection != null) {
                    copySection(defaultSubSection, mergedSubSection);
                    logger.info("Added new config subsection: " + merged.getCurrentPath() + "." + key);
                }
            } else {
                if (existing != null && existing.contains(key)) {
                    merged.set(key, existing.get(key));
                } else {
                    merged.set(key, defaults.get(key));
                    logger.info("Added new config option: " + merged.getCurrentPath() + "." + key + " = " + defaults.get(key));
                }
            }
        }
    }

    private void copySection(ConfigurationSection source, ConfigurationSection target) {
        if (source == null || target == null) return;

        for (String key : source.getKeys(false)) {
            if (source.isConfigurationSection(key)) {
                ConfigurationSection sourceSubSection = source.getConfigurationSection(key);
                if (sourceSubSection != null) {
                    copySection(sourceSubSection, target.createSection(key));
                }
            } else {
                target.set(key, source.get(key));
            }
        }
    }

    /**
     * Fallback migration using standard Bukkit config merge (no comment preservation)
     */
    private void fallbackMigration(File configFile, FileConfiguration existingConfig) throws IOException {
        logger.info("Using fallback migration (comments will not be preserved)");

        FileConfiguration defaultConfig = getDefaultConfig();
        FileConfiguration migratedConfig = mergeConfigs(existingConfig, defaultConfig);
        migratedConfig.set("config-version", CURRENT_CONFIG_VERSION);

        migratedConfig.save(configFile);
        plugin.reloadConfig();
    }

    public static int getCurrentConfigVersion() {
        return CURRENT_CONFIG_VERSION;
    }
}