package dev.mja00.villagerLobotimizer;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class LobotomizeCommand {
    private final VillagerLobotimizer plugin;

    public LobotomizeCommand(VillagerLobotimizer plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> createCommand(final String commandName) {
        return Commands.literal(commandName).requires((command) -> command.getSender().isOp())
                .then(Commands.literal("info").executes((command) -> infoCommand(command.getSource())))
                .then(Commands.literal("debug").executes((command) -> debugCommand(command.getSource()))
                        .then(Commands.argument("villager", ArgumentTypes.entity()).executes((command) -> debugCommandSpecific(command.getSource(), command.getArgument("villager", EntitySelectorArgumentResolver.class))))
                        .then(Commands.literal("toggle").executes((command) -> toggleDebugCommand(command.getSource()))))
                .then(Commands.literal("status").executes((command) -> statusCommand(command.getSource())))
                .then(Commands.literal("wake").executes((command) -> wakeCommand(command.getSource())))
                .then(Commands.literal("reload").executes((command) -> reloadCommand(command.getSource())))
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
                .append(Component.text(String.valueOf(Math.round((double)10000.0F * (double)active / (double)Math.max(1, total)) / (double)100.0F)).color(NamedTextColor.GREEN))
                .append(Component.text("%)"));
        message = message.append(Component.text("\nLobotomized: "))
                .append(Component.text(String.valueOf(inactive)).color(NamedTextColor.RED))
                .append(Component.text(" ("))
                .append(Component.text(String.valueOf(Math.round((double)10000.0F * (double)inactive / (double)Math.max(1, total)) / (double)100.0F)).color(NamedTextColor.GREEN))
                .append(Component.text("%)"));
        sender.sendMessage(message);

        return Command.SINGLE_SUCCESS;
    }

    private int statusCommand(CommandSourceStack source) throws CommandSyntaxException {
        CommandSender sender = source.getSender();

        // Get current TPS
        double currentTps = this.plugin.getServer().getTPS()[0]; // 1-minute average

        Component message = Component.text("Block Change Detection: ")
                .append(Component.text(this.plugin.getStorage().isBlockChangeDetectionEnabled() ? "Enabled" : "Disabled")
                        .color(this.plugin.getStorage().isBlockChangeDetectionEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED));

        message = message.append(Component.text("\nTPS-Based Detection: "))
                .append(Component.text(this.plugin.getStorage().isTpsBasedDetectionEnabled() ? "Enabled" : "Disabled")
                        .color(this.plugin.getStorage().isTpsBasedDetectionEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED));

        message = message.append(Component.text("\nTPS Threshold: "))
                .append(Component.text(String.valueOf(this.plugin.getStorage().getTpsThreshold()))
                        .color(NamedTextColor.YELLOW));

        message = message.append(Component.text("\nCurrent TPS: "))
                .append(Component.text(String.format("%.2f", currentTps))
                        .color(currentTps < this.plugin.getStorage().getTpsThreshold() ? NamedTextColor.RED : NamedTextColor.GREEN));

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
        Component message = Component.text("Is Awareness enabled: ")
                .append(Component.text(villager.isAware()).color(villager.isAware() ? NamedTextColor.GREEN : NamedTextColor.RED));
        message = message.append(Component.text("\nIs AI enabled: "))
                .append(Component.text(villager.hasAI()).color(villager.hasAI() ? NamedTextColor.GREEN : NamedTextColor.RED));
        message = message.append(Component.text("\nIs marked as Lobotomized: "))
                .append(Component.text(lobotomized).color(lobotomized ? NamedTextColor.GREEN : NamedTextColor.RED));
        message = message.append(Component.text("\nIs marked as Active: "))
                .append(Component.text(active).color(active ? NamedTextColor.GREEN : NamedTextColor.RED));
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
}