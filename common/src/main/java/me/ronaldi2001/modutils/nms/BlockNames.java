package me.ronaldi2001.modutils.nms;

import me.ronaldi2001.modutils.modCompat.api.BlockNameSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * The names plugins give their custom blocks, from every registered {@link BlockNameSource}.
 */
public final class BlockNames {
	private BlockNames() {
	}

	/**
	 * @return the icon a plugin gives its custom block, or null if none does
	 */
	public static @Nullable ItemStack icon(Block block) {
		for (RegisteredServiceProvider<BlockNameSource> registration : Bukkit.getServicesManager().getRegistrations(BlockNameSource.class)) {
			try {
				org.bukkit.inventory.ItemStack icon = registration.getProvider().blockIcon(block);
				if (icon != null && !icon.getType().isAir() && icon.getAmount() > 0) return CraftItemStack.asNMSCopy(icon);
			} catch (RuntimeException e) {
				// A plugin that fails to name its block is left out
			}
		}
		return null;
	}

	/**
	 * @return the name rendered for the language, or null if no plugin names this block
	 */
	public static @Nullable Component lookup(Block block, Locale locale) {
		for (RegisteredServiceProvider<BlockNameSource> registration : Bukkit.getServicesManager().getRegistrations(BlockNameSource.class)) {
			net.kyori.adventure.text.Component name;
			try {
				name = registration.getProvider().blockName(block, locale);
			} catch (RuntimeException e) {
				continue;
			}
			if (name != null) return AdventureBridge.asVanilla(ItemText.renderName(name, locale));
		}
		return null;
	}
}
