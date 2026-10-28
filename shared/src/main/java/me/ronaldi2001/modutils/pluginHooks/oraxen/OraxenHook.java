package me.ronaldi2001.modutils.pluginHooks.oraxen;

import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.items.ItemBuilder;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.pluginHooks.CustomItemSource;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Oraxen's custom items, by id.
 * <p>
 * Compiled against Oraxen 1.220.1; {@code OraxenItems.getItemById} and {@code ItemBuilder.build} are the same in 1.163.0,
 * 1.180.0, 1.195.0, 1.212.0 and 1.220.1.
 */
public final class OraxenHook implements CustomItemSource {
	private static final String PLUGIN = "Oraxen";

	private OraxenHook() {
	}

	public static @Nullable OraxenHook load(ModUtilsPlugin plugin) {
		if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) return null;

		try {
			OraxenItems.class.getMethod("getItemById", String.class);
			plugin.getLogger().info("OraxenHook hooked into Oraxen.");
			return new OraxenHook();
		} catch (ReflectiveOperationException | LinkageError e) {
			plugin.getLogger().warning("Oraxen is installed, but its API is not a version this plugin supports; "
					+ "its items will not be shown in recipes.");
			return null;
		}
	}

	@Override
	public String prefix() {
		return "oraxen";
	}

	@Override
	public @Nullable ItemStack itemStack(String itemId) {
		try {
			ItemBuilder builder = OraxenItems.getItemById(itemId);
			return builder == null ? null : builder.build();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
