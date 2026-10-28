package me.ronaldi2001.modutils.pluginHooks.itemsadder;

import dev.lone.itemsadder.api.CustomStack;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.pluginHooks.CustomItemSource;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * ItemsAdder's custom items, by namespaced id ({@code namespace:id}).
 * <p>
 * Compiled against the ItemsAdder API 4.0.17; 4.0.17 and 4.0.18-beta-10 have the same {@code CustomStack}. ItemsAdder
 * loads its items asynchronously, so an id can be unknown for a while after startup.
 */
public final class ItemsAdderHook implements CustomItemSource {
	private static final String PLUGIN = "ItemsAdder";

	private ItemsAdderHook() {
	}

	public static @Nullable ItemsAdderHook load(ModUtilsPlugin plugin) {
		if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) return null;

		try {
			CustomStack.class.getMethod("getInstance", String.class);
			plugin.getLogger().info("ItemsAdderHook hooked into ItemsAdder.");
			return new ItemsAdderHook();
		} catch (ReflectiveOperationException | LinkageError e) {
			plugin.getLogger().warning("ItemsAdder is installed, but its API is not a version this plugin supports; "
					+ "its items will not be shown in recipes.");
			return null;
		}
	}

	@Override
	public String prefix() {
		return "itemsadder";
	}

	@Override
	public @Nullable ItemStack itemStack(String itemId) {
		try {
			CustomStack stack = CustomStack.getInstance(itemId);
			return stack == null ? null : stack.getItemStack();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
