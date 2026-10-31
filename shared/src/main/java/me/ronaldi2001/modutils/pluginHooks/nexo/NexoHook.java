package me.ronaldi2001.modutils.pluginHooks.nexo;

import com.nexomc.nexo.api.NexoBlocks;
import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import com.nexomc.nexo.mechanics.custom_block.CustomBlockMechanic;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.modCompat.api.BlockNameSource;
import me.ronaldi2001.modutils.pluginHooks.ItemNames;
import me.ronaldi2001.modutils.pluginHooks.CustomItemSource;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * Nexo's custom items, by id.
 * <p>
 * Compiled against Nexo 1.29.1; {@code NexoItems.optionalItemFromId} and {@code ItemBuilder.build} are the same in
 * 1.15.0, 1.19.0, 1.25.0 and 1.29.1.
 */
public final class NexoHook implements CustomItemSource, BlockNameSource {
	private static final String PLUGIN = "Nexo";

	private NexoHook() {
	}

	public static @Nullable NexoHook load(ModUtilsPlugin plugin) {
		if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) return null;

		try {
			NexoItems.class.getMethod("optionalItemFromId", String.class);
			plugin.getLogger().info("NexoHook hooked into Nexo.");
			return new NexoHook();
		} catch (ReflectiveOperationException | LinkageError e) {
			plugin.getLogger().warning("Nexo is installed, but its API is not a version this plugin supports; "
					+ "its items will not be shown in recipes.");
			return null;
		}
	}

	@Override
	public String prefix() {
		return "nexo";
	}

	@Override
	public @Nullable ItemStack itemStack(String itemId) {
		try {
			Optional<ItemBuilder> builder = NexoItems.optionalItemFromId(itemId);
			return builder.map(ItemBuilder::build).orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	@Override
	public @Nullable String itemId(ItemStack stack) {
		try {
			return NexoItems.idFromItem(stack);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The item a Nexo block (note block, string, chorus or the like) places, or null if the block isn't Nexo's. */
	private @Nullable ItemStack blockItem(Block block) {
		try {
			CustomBlockMechanic mechanic = NexoBlocks.customBlockMechanic(block);
			return mechanic == null ? null : this.itemStack(mechanic.getItemID());
		} catch (RuntimeException e) {
			return null;
		}
	}

	@Override
	public @Nullable Component blockName(Block block, Locale locale) {
		ItemStack item = this.blockItem(block);
		return item == null ? null : ItemNames.of(item);
	}

	@Override
	public @Nullable ItemStack blockIcon(Block block) {
		return this.blockItem(block);
	}
}
