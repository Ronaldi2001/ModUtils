package me.ronaldi2001.modutils.pluginHooks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.Nullable;

/**
 * The name of an item that works on Spigot as well as Paper: {@code ItemStack.effectiveName()} is Paper's alone.
 */
public final class ItemNames {
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
	private static final boolean PAPER = hasEffectiveName();

	private ItemNames() {
	}

	private static boolean hasEffectiveName() {
		try {
			ItemStack.class.getMethod("effectiveName");
			return true;
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	/**
	 * @return the custom name of the stack, else its item name, or null if it has neither
	 */
	@SuppressWarnings("deprecation") // The legacy string names are the ones Spigot has
	public static @Nullable Component of(ItemStack item) {
		if (PAPER) return item.effectiveName();

		ItemMeta meta = item.getItemMeta();
		if (meta == null) return null;
		if (meta.hasDisplayName()) return LEGACY.deserialize(meta.getDisplayName());
		try {
			if (meta.hasItemName()) return LEGACY.deserialize(meta.getItemName());
		} catch (NoSuchMethodError e) {
			// Spigot before 1.20.5 has no item name
		}
		return null;
	}
}
