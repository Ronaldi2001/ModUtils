package me.ronaldi2001.modutils.modCompat.api;

import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Builds the real item of a plugin's custom items from a copy that came back from a client. Polymer clients get a creative
 * tab with every custom item in it, but what the tab holds is only a look-alike of each item: how it looks, with whatever
 * data the first copy the server saw happened to carry. When a player takes one, this is asked for the real item, so the
 * plugin can build it the way it builds any other (its own data, its own components).
 * <p>
 * CraftEngine, ItemsAdder, Nexo and Oraxen items are covered by Mod Utils itself. Any other plugin registers one with
 * Bukkit's ServicesManager:
 * <pre>
 * Bukkit.getServicesManager().register(ItemRebuilder.class, rebuilder, plugin, ServicePriority.Normal);
 * </pre>
 * Every registered rebuilder is asked in turn, highest priority first, until one returns an item.
 * <p>
 * Called on the thread that reads the client's packets, so it must not touch the world and has to be thread safe.
 */
public interface ItemRebuilder {
	/**
	 * @param stack the look-alike the client sent back; its amount is how many the player took
	 * @return the real item, which should keep the amount of {@code stack} (it is set again if not), or null if the
	 * stack isn't one of this plugin's items
	 */
	@Nullable ItemStack rebuild(ItemStack stack);
}
