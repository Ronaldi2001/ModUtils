package me.ronaldi2001.modutils.nms.compat.jei;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

import java.util.logging.Logger;

/**
 * How custom results are shown in JEI.
 * <p>
 * JEI can't have items added to it from the server, so custom results are swapped for an item JEI does list, which
 * wears the custom item's model and name.
 *
 * @param displayItem the item custom results are shown as
 */
record RecipeDisplayConfig(Item displayItem) {
	/**
	 * Used when the configured display item is missing or can't be used. It is in a creative tab on every version,
	 * has no recipes and is used in none.
	 */
	private static final Item FALLBACK = Items.DRAGON_EGG;

	static RecipeDisplayConfig parse(ConfigurationSection section, Logger logger) {
		String configured = section.getString("customResultItem", "minecraft:dragon_egg");
		Item display = lookup(configured);
		if (display == null) {
			logger.warning("[JEI] customResultItem: unknown item '" + configured + "', using minecraft:dragon_egg");
			display = FALLBACK;
		}
		return new RecipeDisplayConfig(display);
	}

	private static @Nullable Item lookup(String name) {
		Identifier id = Identifier.tryParse(name.trim().toLowerCase());
		return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
	}
}
