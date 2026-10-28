package me.ronaldi2001.modutils.pluginHooks.craftengine;

import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.pluginHooks.CustomItemSource;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.item.recipe.Ingredient;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CraftEngine's custom items and recipes. CraftEngine builds some recipes (smithing, for one) out of plain stand-in
 * items, the base material of each custom item, which is all the server's own recipe data has. This tells what the
 * recipe really takes.
 * <p>
 * Compiled against CraftEngine 26.5 and newer, whose API has not changed between 26.5 and 26.9.1. Earlier versions
 * (0.0.x) have a different item and recipe API, so the hook stays off for them. Nothing in the public methods exposes a
 * CraftEngine type, so the rest of the plugin never needs it on its class path.
 */
public final class CraftEngineHook implements CustomItemSource {
	private static final String PLUGIN = "CraftEngine";

	private CraftEngineHook() {
	}

	/**
	 * @return the hook, or null when CraftEngine isn't installed or its API isn't the one this was written for
	 */
	public static @Nullable CraftEngineHook load(ModUtilsPlugin plugin) {
		if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) return null;

		try {
			// Fails here, not on first use, when the API of this CraftEngine isn't the one compiled against
			CraftEngineItems.class.getMethod("byId", String.class);
			CraftEngine.class.getMethod("recipeManager");
			plugin.getLogger().info("CraftEngineHook hooked into CraftEngine.");
			return new CraftEngineHook();
		} catch (ReflectiveOperationException | LinkageError e) {
			plugin.getLogger().warning("CraftEngine is installed, but its API is not a version this plugin supports (26.5 or newer); "
					+ "custom items in its recipes will show as plain items in JEI.");
			return null;
		}
	}

	@Override
	public String prefix() {
		return "craftengine";
	}

	/**
	 * @param itemId a CraftEngine item id, e.g. {@code default:topaz}
	 * @return the item, or null if CraftEngine has no such item
	 */
	@Override
	public @Nullable ItemStack itemStack(String itemId) {
		try {
			BukkitItemDefinition definition = CraftEngineItems.byId(itemId);
			return definition == null ? null : definition.buildBukkitItem();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * @param recipeKey the key of a CraftEngine recipe, e.g. {@code default:topaz_trident}
	 * @return the item ids each ingredient of the recipe takes, one list per ingredient (template, base, addition for
	 * smithing), or an empty list if it isn't a CraftEngine recipe
	 */
	public List<List<String>> recipeIngredients(String recipeKey) {
		try {
			Optional<Recipe> recipe = CraftEngine.instance().recipeManager().recipeById(Key.of(recipeKey));
			if (recipe.isEmpty()) return List.of();

			List<List<String>> out = new ArrayList<>();
			for (Ingredient ingredient : recipe.get().ingredientsInUse()) {
				List<String> ids = new ArrayList<>();
				for (UniqueKey item : ingredient.items()) ids.add(item.key().toString());
				out.add(ids);
			}
			return out;
		} catch (RuntimeException e) {
			return List.of();
		}
	}
}
