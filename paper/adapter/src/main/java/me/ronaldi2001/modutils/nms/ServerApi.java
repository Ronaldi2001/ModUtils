package me.ronaldi2001.modutils.nms;

import net.minecraft.world.item.component.CustomData;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.loot.Lootable;

import java.util.Locale;
import java.util.UUID;

/**
 * The Bukkit API calls that differ between Paper and Spigot, as Paper has them: its own, cheaper ones. The Bukkit adapters
 * have a file with the same path (bukkit/adapter) that does the same with what Spigot has.
 */
public final class ServerApi {
	private ServerApi() {
	}

	public static Locale locale(Player player) {
		return player.locale();
	}

	/** The state of a block without taking a snapshot of it. */
	public static BlockState blockState(Block block) {
		return block.getState(false);
	}

	/** The owner of a tamed animal, or null. */
	public static UUID ownerId(Tameable tameable) {
		return tameable.getOwnerUniqueId();
	}

	/** Ticks until the chicken lays its next egg; {@link Integer#MAX_VALUE} if the server cannot tell. */
	public static int eggLayTime(Chicken chicken) {
		return chicken.getEggLayTime();
	}

	public static boolean hasLootTable(Lootable lootable) {
		return lootable.hasLootTable();
	}

	public static boolean hasKey(CustomData data, String key) {
		return data.contains(key);
	}
}
