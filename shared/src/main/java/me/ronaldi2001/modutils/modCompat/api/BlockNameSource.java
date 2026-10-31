package me.ronaldi2001.modutils.modCompat.api;

import net.kyori.adventure.text.Component;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Names the custom blocks of a plugin. A custom block is a vanilla block to the server (a cactus, say), so Jade and The
 * One Probe would show that block's name. Plugins register one with Bukkit's ServicesManager to have them show the real
 * one:
 * <pre>
 * Bukkit.getServicesManager().register(BlockNameSource.class, source, plugin, ServicePriority.Normal);
 * </pre>
 * Used when the block has no custom name of its own (an anvil-renamed chest still shows that name). The name and the
 * icon are asked for separately. The blocks of CraftEngine, ItemsAdder, Nexo and Oraxen are covered by Mod Utils itself.
 * <p>
 * The result goes through {@link ItemTextRenderer}s and Adventure's global translator, so it may be a translatable
 * component. Called on whichever thread is building the packet, so it has to be thread safe.
 */
public interface BlockNameSource {
	/**
	 * @return the name of the block for this language, or null if it isn't this plugin's block
	 */
	@Nullable Component blockName(Block block, Locale locale);

	/**
	 * @return the item to show as the block's icon, since the item of the vanilla block it stands in for is the wrong
	 * one (or there is none), or null to leave the icon alone
	 */
	default @Nullable ItemStack blockIcon(Block block) {
		return null;
	}
}
