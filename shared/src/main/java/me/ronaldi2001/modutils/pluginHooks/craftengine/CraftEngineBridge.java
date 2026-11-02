package me.ronaldi2001.modutils.pluginHooks.craftengine;

import me.ronaldi2001.modutils.modCompat.api.StorageSource;
import me.ronaldi2001.modutils.pluginHooks.StorageEntries;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.block.entity.DrawerBlockEntityController;
import net.momirealms.craftengine.bukkit.block.entity.SimpleStorageBlockEntityController;
import net.momirealms.craftengine.bukkit.entity.furniture.behavior.DisplayItemFurnitureBehaviorTemplate;
import net.momirealms.craftengine.bukkit.entity.furniture.behavior.SimpleStorageFurnitureBehaviorTemplate;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.recipe.Ingredient;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The calls into CraftEngine, behind {@link CraftEngineHook}. Only loaded once CraftEngine is known to be there: the JVM
 * checks a class's methods when it loads it, and that loads the CraftEngine classes they use, which a server without
 * CraftEngine doesn't have. So no CraftEngine type may appear in {@link CraftEngineHook}, which the plugin loads always.
 * <p>
 * Compiled against CraftEngine 26.5 and newer, whose API has not changed between 26.5 and 26.9.1. Earlier versions
 * (0.0.x) have a different item and recipe API.
 */
final class CraftEngineBridge {
	CraftEngineBridge() {
	}

	/**
	 * Fails here, not on first use, when the API of this CraftEngine isn't the one compiled against.
	 */
	static void verify() throws ReflectiveOperationException {
		CraftEngineItems.class.getMethod("byId", String.class);
		CraftEngine.class.getMethod("recipeManager");
	}

	/**
	 * @param itemId a CraftEngine item id, e.g. {@code default:topaz}
	 * @return the item, or null if CraftEngine has no such item
	 */
	@Nullable ItemStack itemStack(String itemId) {
		try {
			BukkitItemDefinition definition = CraftEngineItems.byId(itemId);
			return definition == null ? null : definition.buildBukkitItem();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * The name of a CraftEngine block: the name of the item it places, which is what CraftEngine itself gives it, or its
	 * translation key when it has no item.
	 */
	@Nullable Component blockName(Block block, Locale locale) {
		try {
			ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
			if (state == null) return null;

			Key itemId = state.settings().itemId();
			if (itemId != null) {
				Component name = CraftEngineNames.itemName(itemId.toString());
				if (name != null) return name;
			}
			return Component.translatable(state.owner().value().translationKey());
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * The contents of a CraftEngine storage block (the safe, the drawer, ...), which keeps its items in its own block
	 * entity instead of a vanilla container. Other custom blocks are not handled.
	 */
	@Nullable List<StorageSource.Entry> itemsAt(Block block) {
		try {
			if (!CraftEngineBlocks.isCustomBlock(block)) return null;

			BlockEntity entity = BukkitAdaptor.adapt(block.getWorld()).ceWorld()
					.getBlockEntityAtIfLoaded(new BlockPos(block.getX(), block.getY(), block.getZ()));
			if (entity == null || entity.controller == null) return null;

			// A block with several behaviours has a controller that holds one per behaviour
			for (int i = 0; i < 4; i++) {
				SimpleStorageBlockEntityController storage = entity.controller.get(SimpleStorageBlockEntityController.class, i);
				if (storage != null) return StorageEntries.of(Arrays.asList(storage.inventory().getContents()));

				DrawerBlockEntityController drawer = entity.controller.get(DrawerBlockEntityController.class, i);
				if (drawer != null) return drawerEntries(drawer);
			}
			return null;
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/**
	 * The contents of CraftEngine furniture (the showcase, storage furniture). Clients see its hitboxes as entities the
	 * server doesn't have, so Jade asks by the id of the one it looks at.
	 */
	@Nullable List<StorageSource.Entry> itemsOfEntity(World world, int entityId) {
		try {
			FurnitureManager manager = BukkitCraftEngine.instance().furnitureManager();
			Furniture furniture = manager.loadedFurnitureByInteractableEntityId(entityId);
			if (furniture == null) furniture = manager.loadedFurnitureByMetaEntityId(entityId);
			if (furniture == null) furniture = manager.loadedFurnitureByColliderEntityId(entityId);
			if (furniture == null) return null;

			for (int i = 0; i < 4; i++) {
				DisplayItemFurnitureBehaviorTemplate.DisplayItemFurnitureController display =
						furniture.controller.get(DisplayItemFurnitureBehaviorTemplate.DisplayItemFurnitureController.class, i);
				if (display != null) {
					// The item on display is package-private
					Object item = field(display, "savedItem");
					if (item instanceof Item held && !held.isEmpty() && held.platformItem() instanceof ItemStack stack) {
						return StorageEntries.of(List.of(stack.asQuantity(Math.max(1, held.count()))));
					}
					return List.of();
				}

				SimpleStorageFurnitureBehaviorTemplate.SimpleStorageFurnitureController storage =
						furniture.controller.get(SimpleStorageFurnitureBehaviorTemplate.SimpleStorageFurnitureController.class, i);
				if (storage != null) {
					return field(storage, "inventory") instanceof Inventory inventory ? StorageEntries.of(Arrays.asList(inventory.getContents())) : null;
				}
			}
			return null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	private static @Nullable Object field(Object owner, String name) throws ReflectiveOperationException {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	/** A drawer holds one kind of item in a count far beyond a stack. */
	private static List<StorageSource.Entry> drawerEntries(DrawerBlockEntityController drawer) {
		Item item = drawer.item();
		int count = drawer.itemCount();
		if (item == null || item.isEmpty() || count <= 0 || !(item.platformItem() instanceof ItemStack stack)) return List.of();
		return List.of(new StorageSource.Entry(stack.asOne(), count));
	}

	@Nullable ItemStack blockIcon(Block block) {
		try {
			ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
			if (state == null) return null;

			Key itemId = state.settings().itemId();
			return itemId == null ? null : this.itemStack(itemId.toString());
		} catch (RuntimeException e) {
			return null;
		}
	}

	@Nullable String itemId(ItemStack stack) {
		try {
			Key id = CraftEngineItems.getCustomItemId(stack);
			return id == null ? null : id.toString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * @param recipeKey the key of a CraftEngine recipe, e.g. {@code default:topaz_trident}
	 * @return the item ids each ingredient of the recipe takes, one list per ingredient (template, base, addition for
	 * smithing), or an empty list if it isn't a CraftEngine recipe
	 */
	List<List<String>> recipeIngredients(String recipeKey) {
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
