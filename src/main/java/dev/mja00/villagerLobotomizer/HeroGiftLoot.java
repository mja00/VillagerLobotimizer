package dev.mja00.villagerLobotomizer;

import java.util.Collection;
import java.util.List;
import java.util.Random;

import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.LootTables;
import org.jetbrains.annotations.NotNull;

/** Rolls a villager's Hero of the Village gift. A seam so tests need not resolve real loot tables. */
@FunctionalInterface
public interface HeroGiftLoot {

    @NotNull Collection<ItemStack> roll(@NotNull Villager villager, @NotNull LootTables table, @NotNull Random random);

    /** Vanilla's gift tables, given the villager as the looted entity and origin, as vanilla does. */
    static @NotNull HeroGiftLoot vanilla() {
        return (villager, table, random) -> {
            LootTable lootTable = table.getLootTable();
            if (lootTable == null) {
                return List.of();
            }
            LootContext context = new LootContext.Builder(villager.getLocation()).lootedEntity(villager).build();
            return lootTable.populateLoot(random, context);
        };
    }
}
