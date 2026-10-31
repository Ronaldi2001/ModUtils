package me.ronaldi2001.modutils.pluginHooks.craftengine;

import me.ronaldi2001.modutils.pluginHooks.ItemNames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.processor.ItemNameProcessor;
import net.momirealms.craftengine.core.item.processor.ItemProcessor;
import net.momirealms.craftengine.core.item.processor.OverwritableItemNameProcessor;
import net.momirealms.craftengine.core.util.AdventureHelper;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Item names from CraftEngine's item config. Kept out of {@link CraftEngineHook}: the JVM checks a class's methods when it
 * loads it, and some of these calls would then load CraftEngine classes on a server that doesn't have CraftEngine.
 */
final class CraftEngineNames {
	private CraftEngineNames() {
	}

	/**
	 * CraftEngine puts an item's name on only when it sends the item to a client, so the item built for the server still
	 * has its base material's name. This is the name from the item's config.
	 */
	static @Nullable Component itemName(String itemId) {
		BukkitItemDefinition definition = CraftEngineItems.byId(itemId);
		if (definition == null) return null;

		for (ItemProcessor[] processors : List.of(definition.clientBoundProcessors(), definition.processors())) {
			for (ItemProcessor processor : processors) {
				if (processor instanceof OverwritableItemNameProcessor overwritable) {
					processor = wrappedName(overwritable);
				}
				if (processor instanceof ItemNameProcessor name) {
					// CraftEngine bundles its own copy of Adventure: go over JSON to the server's
					String json = AdventureHelper.componentToJson(AdventureHelper.deserialize(name.itemName(), ItemBuildContext.empty()));
					return GsonComponentSerializer.gson().deserialize(json);
				}
			}
		}
		return ItemNames.of(definition.buildBukkitItem());
	}

	/** The overwritable name keeps its name processor private. */
	private static @Nullable ItemProcessor wrappedName(OverwritableItemNameProcessor processor) {
		try {
			Field field = OverwritableItemNameProcessor.class.getDeclaredField("modifier");
			field.setAccessible(true);
			return (ItemProcessor) field.get(processor);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
