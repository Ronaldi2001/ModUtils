package me.ronaldi2001.modutils.modCompat.api;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A custom block storage whose items aren't in a vanilla container (for example a barrel that keeps its contents in
 * plugin data). Other plugins register one with Bukkit's ServicesManager:
 * <pre>
 * Bukkit.getServicesManager().register(StorageSource.class, source, plugin, ServicePriority.Normal);
 * </pre>
 * Jade and The One Probe then show the contents in their tooltips.
 */
public interface StorageSource {
	/**
	 * @return the items to show instead of the block's own container, or null if this source doesn't handle the
	 * block. An empty list means it does, but there is nothing inside.
	 */
	@Nullable List<Entry> itemsAt(Block block);

	/**
	 * The contents of storage that is an entity the server has (furniture that keeps its items on a display or an
	 * interaction entity).
	 *
	 * @return the items to show, or null if this source doesn't know the entity
	 */
	default @Nullable List<Entry> itemsOf(Entity entity) {
		return null;
	}

	/**
	 * The contents of storage that is an entity the server doesn't have, such as furniture that clients see as packet-only
	 * entities. Jade asks about the entity the player looks at by its id.
	 *
	 * @return the items to show, or null if this source doesn't know the entity
	 */
	default @Nullable List<Entry> itemsOfEntity(World world, int entityId) {
		return null;
	}

	/**
	 * @param template the item (one is enough, its amount is ignored)
	 * @param amount   how many of them, may exceed the stack size
	 */
	record Entry(ItemStack template, int amount) {
	}
}
