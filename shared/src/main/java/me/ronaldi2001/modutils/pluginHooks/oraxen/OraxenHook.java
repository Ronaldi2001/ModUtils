package me.ronaldi2001.modutils.pluginHooks.oraxen;

import io.th0rgal.oraxen.api.OraxenBlocks;
import io.th0rgal.oraxen.api.OraxenFurniture;
import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.items.ItemBuilder;
import io.th0rgal.oraxen.mechanics.Mechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.furniture.FurnitureMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.storage.StorageMechanic;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.modCompat.api.BlockNameSource;
import me.ronaldi2001.modutils.modCompat.api.StorageSource;
import me.ronaldi2001.modutils.pluginHooks.StorageEntries;
import me.ronaldi2001.modutils.pluginHooks.ItemNames;
import me.ronaldi2001.modutils.pluginHooks.CustomItemSource;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Oraxen's custom items, by id.
 * <p>
 * Compiled against Oraxen 1.220.1; {@code OraxenItems.getItemById} and {@code ItemBuilder.build} are the same in 1.163.0,
 * 1.180.0, 1.195.0, 1.212.0 and 1.220.1.
 */
public final class OraxenHook implements CustomItemSource, BlockNameSource, StorageSource {
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

	@Override
	public @Nullable String itemId(ItemStack stack) {
		try {
			return OraxenItems.getIdByItem(stack);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The item an Oraxen block (note block, string, chorus or shaped) places, or null if the block isn't Oraxen's. */
	private @Nullable ItemStack blockItem(Block block) {
		try {
			Mechanic mechanic = OraxenBlocks.getOraxenBlock(block.getLocation());
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

	/** Whether the storage keeps one shared set of items, which are what can be shown (not a player's own, or none). */
	private static boolean shared(@Nullable StorageMechanic storage) {
		return storage != null && (storage.isStorage() || storage.isShulker());
	}

	/** The contents of an Oraxen storage block, or of storage furniture a block of is part of. */
	@Override
	public @Nullable List<Entry> itemsAt(Block block) {
		try {
			StorageMechanic storage = blockStorage(block);
			if (storage != null) return shared(storage) ? StorageEntries.of(Arrays.asList(storage.getStorageContents(block))) : null;

			// Furniture's hitbox is a block, but its items are kept on the entity it stands for
			FurnitureMechanic furniture = OraxenFurniture.getFurnitureMechanic(block);
			if (furniture == null || !shared(furniture.getStorage())) return null;
			for (Entity near : block.getWorld().getNearbyEntities(block.getLocation().add(0.5, 0.5, 0.5), 2, 2, 2)) {
				if (OraxenFurniture.isBaseEntity(near) && OraxenFurniture.getFurnitureMechanic(near) == furniture) {
					return StorageEntries.of(Arrays.asList(furniture.getStorage().getStorageContents(near)));
				}
			}
			return null;
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	private static @Nullable StorageMechanic blockStorage(Block block) {
		var noteBlock = OraxenBlocks.getNoteBlockMechanic(block);
		if (noteBlock != null) return noteBlock.getStorage();
		var string = OraxenBlocks.getStringMechanic(block);
		if (string != null) return string.getStorage();
		var chorus = OraxenBlocks.getChorusMechanic(block);
		return chorus == null ? null : chorus.getStorage();
	}

	/** The contents of storage furniture, from its base entity or one of its hitbox entities. */
	@Override
	public @Nullable List<Entry> itemsOf(Entity entity) {
		try {
			FurnitureMechanic furniture = OraxenFurniture.getFurnitureMechanic(entity);
			if (furniture == null || !shared(furniture.getStorage())) return null;
			return StorageEntries.of(Arrays.asList(furniture.getStorage().getStorageContents(entity)));
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}
}
