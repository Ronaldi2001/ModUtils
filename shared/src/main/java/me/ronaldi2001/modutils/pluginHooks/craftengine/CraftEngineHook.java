package me.ronaldi2001.modutils.pluginHooks.craftengine;

import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.modCompat.api.BlockNameSource;
import me.ronaldi2001.modutils.modCompat.api.StorageSource;
import me.ronaldi2001.modutils.pluginHooks.CustomItemSource;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * CraftEngine's custom items and recipes. CraftEngine builds some recipes (smithing, for one) out of plain stand-in
 * items, the base material of each custom item, which is all the server's own recipe data has. This tells what the
 * recipe really takes.
 * <p>
 * This class is loaded on every server, so it must not mention a CraftEngine type anywhere, not even inside a method:
 * the JVM would load it while checking the class, and fail on a server without CraftEngine. Everything that does is in
 * {@link CraftEngineBridge}, which is only loaded once CraftEngine is found. The same goes for the public methods: none
 * exposes a CraftEngine type, so the rest of the plugin never needs it on its class path.
 * <p>
 * Compiled against CraftEngine 26.5 and newer, whose API has not changed between 26.5 and 26.9.1. Earlier versions
 * (0.0.x) have a different item and recipe API, so the hook stays off for them.
 */
public final class CraftEngineHook implements CustomItemSource, BlockNameSource, StorageSource {
	private static final String PLUGIN = "CraftEngine";

	private final CraftEngineBridge bridge;

	private CraftEngineHook(CraftEngineBridge bridge) {
		this.bridge = bridge;
	}

	/**
	 * @return the hook, or null when CraftEngine isn't installed or its API isn't the one this was written for
	 */
	public static @Nullable CraftEngineHook load(ModUtilsPlugin plugin) {
		if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) return null;

		try {
			CraftEngineBridge.verify();
			CraftEngineHook hook = new CraftEngineHook(new CraftEngineBridge());
			plugin.getLogger().info("CraftEngineHook hooked into CraftEngine.");
			return hook;
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
		return this.bridge.itemStack(itemId);
	}

	/**
	 * The name of a CraftEngine block: the name of the item it places, which is what CraftEngine itself gives it, or its
	 * translation key when it has no item.
	 */
	@Override
	public @Nullable Component blockName(Block block, Locale locale) {
		return this.bridge.blockName(block, locale);
	}

	/**
	 * The contents of a CraftEngine storage block (the safe, the drawer, ...), which keeps its items in its own block
	 * entity instead of a vanilla container. Other custom blocks are not handled.
	 */
	@Override
	public @Nullable List<Entry> itemsAt(Block block) {
		return this.bridge.itemsAt(block);
	}

	/**
	 * The contents of CraftEngine furniture (the showcase, storage furniture). Clients see its hitboxes as entities the
	 * server doesn't have, so Jade asks by the id of the one it looks at.
	 */
	@Override
	public @Nullable List<Entry> itemsOfEntity(World world, int entityId) {
		return this.bridge.itemsOfEntity(world, entityId);
	}

	@Override
	public @Nullable ItemStack blockIcon(Block block) {
		return this.bridge.blockIcon(block);
	}

	@Override
	public @Nullable String itemId(ItemStack stack) {
		return this.bridge.itemId(stack);
	}

	/**
	 * @param recipeKey the key of a CraftEngine recipe, e.g. {@code default:topaz_trident}
	 * @return the item ids each ingredient of the recipe takes, one list per ingredient (template, base, addition for
	 * smithing), or an empty list if it isn't a CraftEngine recipe
	 */
	public List<List<String>> recipeIngredients(String recipeKey) {
		return this.bridge.recipeIngredients(recipeKey);
	}
}
