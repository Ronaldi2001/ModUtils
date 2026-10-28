package me.ronaldi2001.modutils.modCompat.api;

import org.bukkit.block.Block;
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
	 * @param template the item (one is enough, its amount is ignored)
	 * @param amount   how many of them, may exceed the stack size
	 */
	record Entry(ItemStack template, int amount) {
	}
}
