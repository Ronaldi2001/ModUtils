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
 * The Bukkit API calls that differ between Paper and Spigot, as Spigot has them. The Paper adapters have a file with the
 * same path (paper/adapter) that does the same with Paper's own calls.
 */
public final class ServerApi {
	private ServerApi() {
	}

	/** Spigot only has the locale as a string, like {@code en_us}. */
	@SuppressWarnings("deprecation")
	public static Locale locale(Player player) {
		String tag = player.getLocale();
		return tag == null || tag.isEmpty() ? Locale.US : Locale.forLanguageTag(tag.replace('_', '-'));
	}

	/** Spigot takes a snapshot of the block. */
	public static BlockState blockState(Block block) {
		return block.getState();
	}

	public static UUID ownerId(Tameable tameable) {
		return tameable.getOwner() == null ? null : tameable.getOwner().getUniqueId();
	}

	/** Spigot cannot tell when a chicken lays its egg. */
	public static int eggLayTime(Chicken chicken) {
		return Integer.MAX_VALUE;
	}

	public static boolean hasLootTable(Lootable lootable) {
		return lootable.getLootTable() != null;
	}

	/** Spigot has no {@code contains} on its custom data, only the tag it holds. */
	public static boolean hasKey(CustomData data, String key) {
		return data.copyTag().contains(key);
	}
}
