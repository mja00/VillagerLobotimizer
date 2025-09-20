package dev.mja00.villagerLobotomizer;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class LobotomizeCommand {
    private final VillagerLobotomizer plugin;

    public LobotomizeCommand(VillagerLobotomizer plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> createCommand(final String commandName) {
        return Commands.literal(commandName).requires((command) -> command.getSender().hasPermission("lobotomy.command"))
                .then(Commands.literal("info").executes((command) -> infoCommand(command.getSource())))
                .then(Commands.literal("debug").executes((command) -> debugCommand(command.getSource()))
                        .then(Commands.argument("villager", ArgumentTypes.entity()).executes((command) -> debugCommandSpecific(command.getSource(), command.getArgument("villager", EntitySelectorArgumentResolver.class))))
                        .then(Commands.literal("toggle").executes((command) -> toggleDebugCommand(command.getSource()))))
                .then(Commands.literal("wake").executes((command) -> wakeCommand(command.getSource())))
                .then(Commands.literal("reload").executes((command) -> reloadCommand(command.getSource())))
                .then(Commands.literal("config")
                        .then(Commands.argument("key", StringArgumentType.string()).suggests(LobotomizeCommand::getConfigKeySuggestions)
                        .then(Commands.literal("get")
                            .executes(
                                (command) -> getConfigCommand(command.getSource(), command.getArgument("key", String.class))
                                )
                            )
                        .then(Commands.literal("set").then(Commands.argument("value", StringArgumentType.string()).executes((command) -> setConfigCommand(command.getSource(), command.getArgument("key", String.class), command.getArgument("value", String.class)))))
                    )
                )
                .build();
    }

    private int infoCommand(CommandSourceStack source) throws CommandSyntaxException {
        int active = this.plugin.getStorage().getActive().size();
        int inactive = this.plugin.getStorage().getLobotomized().size();
        int total = active + inactive;
        CommandSender sender = source.getSender();
        Component message = Component.text("There are ")
                .append(Component.text(String.valueOf(total)).color(NamedTextColor.GREEN))
                .append(Component.text(" known villagers on the server."));
        message = message.append(Component.text("\nActive: "))
                .append(Component.text(String.valueOf(active)).color(NamedTextColor.GREEN))
                .append(Component.text(" ("))
                .append(Component.text(String.valueOf(Math.round((double)10000.0F * (double)active / (double)total) / (double)100.0F)).color(NamedTextColor.GREEN))
                .append(Component.text("%)"));
        message = message.append(Component.text("\nLobotomized: "))
                .append(Component.text(String.valueOf(inactive)).color(NamedTextColor.RED))
                .append(Component.text(" ("))
                .append(Component.text(String.valueOf(Math.round((double)10000.0F * (double)inactive / (double)total) / (double)100.0F)).color(NamedTextColor.GREEN))
                .append(Component.text("%)"));
        sender.sendMessage(message);

        return Command.SINGLE_SUCCESS;
    }

    private int debugCommand(CommandSourceStack source) throws CommandSyntaxException {
        // If the command isn't executed by a player, we don't want to do anything
        Entity target = getLookedTarget(source);
        if (target == null) return 0;
        return this.getVillagerDetails(source, (Villager) target);
    }

    // This ensures the executor is a player and that they are looking at a villager
    private @Nullable Entity getLookedTarget(CommandSourceStack source) {
        if (!(source.getExecutor() instanceof Player player)) {
            source.getSender().sendMessage(Component.text("Only players can use this command."));
            return null;
        }
        Entity target = this.getTargeted(player);
        if (target == null) {
            source.getSender().sendMessage(Component.text("Look at a villager while executing the command, or specify the UUID of a villager."));
            return null;
        }
        return target;
    }

    private int debugCommandSpecific(CommandSourceStack source, EntitySelectorArgumentResolver resolver) throws CommandSyntaxException {
        // If the command isn't executed by a player, we don't want to do anything
        if (!(source.getExecutor() instanceof Player)) {
            source.getSender().sendMessage(Component.text("Only players can use this command."));
            return 0;
        }

        final List<Entity> targets = resolver.resolve(source);
        final Entity target = targets.get(0);

        if (!(target instanceof Villager villager)) {
            source.getSender().sendMessage(Component.text("Look at a villager while executing the command, or specify the UUID of a villager."));
            return 0;
        }

        return getVillagerDetails(source, villager);
    }

    private int getVillagerDetails(CommandSourceStack source, Villager villager) {
        boolean lobotomized = this.plugin.getStorage().getLobotomized().contains(villager);
        boolean active = this.plugin.getStorage().getActive().contains(villager);
        // If they aren't aware we should ignore the hasAI call
        boolean hasAI = villager.isAware() && villager.hasAI();
        Component message = Component.text("Is Awareness enabled: ")
                .append(Component.text(villager.isAware()).color(villager.isAware() ? NamedTextColor.GREEN : NamedTextColor.RED));
        message = message.append(Component.text("\nIs AI enabled: "))
                .append(Component.text(hasAI).color(hasAI ? NamedTextColor.GREEN : NamedTextColor.RED));
        message = message.append(Component.text("\nIs marked as Lobotomized: "))
                .append(Component.text(lobotomized).color(lobotomized ? NamedTextColor.GREEN : NamedTextColor.RED));
        message = message.append(Component.text("\nIs marked as Active: "))
                .append(Component.text(active).color(active ? NamedTextColor.GREEN : NamedTextColor.RED));
        message = message.append(Component.text("\nVillager level: "))
                .append(Component.text(villager.getVillagerLevel()).color(NamedTextColor.GREEN));
        message = message.append(Component.text("\nVillager experience: "))
                .append(Component.text(villager.getVillagerExperience()).color(NamedTextColor.GREEN));
        source.getSender().sendMessage(message);

        return Command.SINGLE_SUCCESS;
    }

    private @Nullable Entity getTargeted(Player player) {
        Entity target = null;

        RayTraceResult ray = player.getWorld().rayTraceEntities(player.getEyeLocation(), player.getEyeLocation().getDirection(), 16.0F, 0.0F, (ex) -> ex instanceof Villager);
        target = ray == null ? null : ray.getHitEntity();

        return target;
    }

    private int wakeCommand(CommandSourceStack source) throws CommandSyntaxException {
        // If the command isn't executed by a player, we don't want to do anything
        Entity target = getLookedTarget(source);
        if (target == null) return 0;
        Villager villager = (Villager) target;
        this.plugin.getStorage().removeVillager(villager);
        source.getSender().sendMessage(Component.text("This villager will now be unaffected by the plugin until the chunk is reloaded."));
        return Command.SINGLE_SUCCESS;
    }

    private int reloadCommand(CommandSourceStack source) throws CommandSyntaxException {
        // Reload our config
        this.plugin.reloadConfig();
        // Now we reload all villagers
        int villagers = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                this.plugin.getStorage().removeVillager(villager);
                // Add it back
                this.plugin.getStorage().addVillager(villager);
                ++villagers;
            }
        }
        Component message = Component.text("Reloaded ")
                .append(Component.text(String.valueOf(villagers)).color(NamedTextColor.GREEN))
                .append(Component.text(" villagers. The config file was also reloaded."));
        source.getSender().sendMessage(message);
        return Command.SINGLE_SUCCESS;
    }

    private int toggleDebugCommand(CommandSourceStack source) throws CommandSyntaxException {
        this.plugin.setDebugging(!this.plugin.isDebugging());
        source.getSender().sendMessage(Component.text("Debug mode: ")
                .append(Component.text(this.plugin.isDebugging() ? "enabled" : "disabled"))
                .append(Component.text(". Messages about villager tracking will now be printed to your console.")));
        return Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> getConfigKeySuggestions(final CommandContext<CommandSourceStack> ctx, final SuggestionsBuilder builder) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getExecutor() instanceof Player)) {
            return builder.buildFuture();
        }

        VillagerLobotomizer plugin = (VillagerLobotomizer) Bukkit.getPluginManager().getPlugin("VillagerLobotimizer");
        if (plugin == null) {
            return builder.buildFuture();
        }

        // Get all config keys dynamically from the config
        Set<String> configKeys = plugin.getConfig().getKeys(true);

        String remaining = builder.getRemaining().toLowerCase();

        // Filter and suggest keys that match the input
        configKeys.stream()
                .filter(key -> key.toLowerCase().startsWith(remaining))
                .forEach(builder::suggest);

        return builder.buildFuture();
    }

    private int getConfigCommand(CommandSourceStack source, String key) throws CommandSyntaxException {
        String value = this.plugin.getConfig().getString(key);
        source.getSender().sendMessage(Component.text("The value of " + key + " is " + value));
        return Command.SINGLE_SUCCESS;
    }

    private int setConfigCommand(CommandSourceStack source, String key, String value) throws CommandSyntaxException {
        // Check if the key exists in the config
        if (!this.plugin.getConfig().contains(key)) {
            source.getSender().sendMessage(Component.text("Config key '" + key + "' does not exist.").color(NamedTextColor.RED));
            return 0;
        }

        // Get the current value to determine the expected type
        Object currentValue = this.plugin.getConfig().get(key);

        try {
            // Determine the type and set the value accordingly
            if (currentValue instanceof Boolean) {
                // Boolean values
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    source.getSender().sendMessage(Component.text("'" + key + "' expects a boolean value (true/false), but got: " + value).color(NamedTextColor.RED));
                    return 0;
                }
                boolean boolValue = Boolean.parseBoolean(value);
                this.plugin.getConfig().set(key, boolValue);
            } else if (currentValue instanceof Number) {
                Number parsed = switch (currentValue) {
                    case Long l -> Long.valueOf(value);
                    case Integer i -> Integer.valueOf(value);
                    case Double d -> Double.valueOf(value);
                    case Float f -> Float.valueOf(value);
                    default -> null;
                };
                if (parsed != null) {
                    this.plugin.getConfig().set(key, parsed);
                }
            } else if (currentValue instanceof String) {
                // String values
                this.plugin.getConfig().set(key, value);
            } else if (currentValue instanceof List) {
                // List values - for now, we'll treat this as adding to the list
                source.getSender().sendMessage(Component.text("Setting list values is not supported yet. Use the config file directly.").color(NamedTextColor.YELLOW));
                return 0;
            } else {
                // Unknown type
                source.getSender().sendMessage(Component.text("Cannot set value for '" + key + "': unsupported type.").color(NamedTextColor.RED));
                return 0;
            }

            // Save the config
            this.plugin.saveConfig();
            this.plugin.reloadConfig();

            // Send success message
            source.getSender().sendMessage(Component.text("Successfully set '" + key + "' to '" + value + "'").color(NamedTextColor.GREEN));
            source.getSender().sendMessage(Component.text("Note: You may need to run '/lobotomy reload' for changes to take effect.").color(NamedTextColor.YELLOW));

            return Command.SINGLE_SUCCESS;

        } catch (Exception e) {
            source.getSender().sendMessage(Component.text("Failed to set config value: " + e.getMessage()).color(NamedTextColor.RED));
            return 0;
        }
    }

}
