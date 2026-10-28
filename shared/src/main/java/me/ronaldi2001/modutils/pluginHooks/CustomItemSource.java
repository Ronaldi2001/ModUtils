package me.ronaldi2001.modutils.pluginHooks;

import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A plugin that has custom items by id (CraftEngine, ItemsAdder, Nexo, Oraxen).
 */
public interface CustomItemSource {
	/**
	 * @return the prefix that picks this plugin in a config id, without the colon: {@code nexo} for {@code nexo:ruby}
	 */
	String prefix();

	/**
	 * @param itemId the id the plugin knows the item by, e.g. {@code default:topaz} or {@code amethyst_sword}
	 * @return the item, or null if this plugin has no such item (or isn't ready yet)
	 */
	@Nullable ItemStack itemStack(String itemId);
}
