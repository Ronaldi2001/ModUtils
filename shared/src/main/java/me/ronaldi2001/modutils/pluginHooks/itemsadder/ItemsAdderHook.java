package me.ronaldi2001.modutils.pluginHooks.itemsadder;

import dev.lone.itemsadder.api.CustomBlock;
import dev.lone.itemsadder.api.CustomFurniture;
import dev.lone.itemsadder.api.CustomStack;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.modCompat.api.BlockNameSource;
import me.ronaldi2001.modutils.modCompat.api.StorageSource;
import me.ronaldi2001.modutils.pluginHooks.StorageEntries;
import me.ronaldi2001.modutils.pluginHooks.ItemNames;
import me.ronaldi2001.modutils.pluginHooks.CustomItemSource;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ItemsAdder's custom items, by namespaced id ({@code namespace:id}).
 * <p>
 * Compiled against the ItemsAdder API 4.0.17; 4.0.17 and 4.0.18-beta-10 have the same {@code CustomStack}. ItemsAdder
 * loads its items asynchronously, so an id can be unknown for a while after startup.
 */
public final class ItemsAdderHook implements CustomItemSource, BlockNameSource, StorageSource {
	private static final String PLUGIN = "ItemsAdder";
	/** The addon that adds the storage behaviour. It has no API: what it keeps is read from its persistent data. */
	private static final String ADDITIONS = "ItemsAdderAdditions";
	private static final String STORAGE_PREFIX = "storage_";

	private ItemsAdderHook() {
	}

	public static @Nullable ItemsAdderHook load(ModUtilsPlugin plugin) {
		if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) return null;

		try {
			CustomStack.class.getMethod("getInstance", String.class);
			plugin.getLogger().info("ItemsAdderHook hooked into ItemsAdder.");
			return new ItemsAdderHook();
		} catch (ReflectiveOperationException | LinkageError e) {
			plugin.getLogger().warning("ItemsAdder is installed, but its API is not a version this plugin supports; "
					+ "its items will not be shown in recipes.");
			return null;
		}
	}

	@Override
	public String prefix() {
		return "itemsadder";
	}

	@Override
	public @Nullable ItemStack itemStack(String itemId) {
		try {
			CustomStack stack = CustomStack.getInstance(itemId);
			return stack == null ? null : stack.getItemStack();
		} catch (RuntimeException e) {
			return null;
		}
	}

	@Override
	public @Nullable String itemId(ItemStack stack) {
		try {
			CustomStack custom = CustomStack.byItemStack(stack);
			return custom == null ? null : custom.getNamespacedID();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The item an ItemsAdder block places, or null if the block isn't ItemsAdder's. */
	private @Nullable ItemStack blockItem(Block block) {
		try {
			CustomBlock custom = CustomBlock.byAlreadyPlaced(block);
			return custom == null ? null : custom.getItemStack();
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

	/**
	 * The contents of a storage block or furniture of ItemsAdderAdditions. It keeps them in persistent data: a block's in
	 * its chunk, under the key {@code x<x>y<y>z<z>} (x and z within the chunk) that holds a container, and a furniture's on
	 * its entity, either way under {@code storage_<item id>} as a byte array. What is shown is what was saved when the
	 * storage was last closed.
	 */
	@Override
	public @Nullable List<Entry> itemsAt(Block block) {
		Plugin additions = additions();
		if (additions == null) return null;

		try {
			if (CustomBlock.byAlreadyPlaced(block) != null) {
				NamespacedKey holder = new NamespacedKey(additions, "x" + (block.getX() & 0xF) + "y" + block.getY() + "z" + (block.getZ() & 0xF));
				PersistentDataContainer data = block.getChunk().getPersistentDataContainer().get(holder, PersistentDataType.TAG_CONTAINER);
				return data == null ? null : read(additions, data);
			}

			CustomFurniture furniture = CustomFurniture.byAlreadySpawned(block);
			return furniture == null ? null : read(additions, furniture.getEntity().getPersistentDataContainer());
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** The contents of storage furniture, from its entity or one of its hitbox entities. */
	@Override
	public @Nullable List<Entry> itemsOf(Entity entity) {
		Plugin additions = additions();
		if (additions == null) return null;

		try {
			CustomFurniture furniture = CustomFurniture.byAlreadySpawned(entity);
			return furniture == null ? null : read(additions, furniture.getEntity().getPersistentDataContainer());
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	private static @Nullable Plugin additions() {
		Plugin plugin = Bukkit.getPluginManager().getPlugin(ADDITIONS);
		return plugin != null && plugin.isEnabled() ? plugin : null;
	}

	/** @return null if the container holds no storage contents */
	private static @Nullable List<Entry> read(Plugin additions, PersistentDataContainer data) {
		String namespace = new NamespacedKey(additions, "storage").getNamespace();
		for (NamespacedKey key : data.getKeys()) {
			if (!key.getNamespace().equals(namespace) || !key.getKey().startsWith(STORAGE_PREFIX)) continue;
			if (!data.has(key, PersistentDataType.BYTE_ARRAY)) continue;

			byte[] bytes = data.get(key, PersistentDataType.BYTE_ARRAY);
			List<ItemStack> stacks = bytes == null ? null : decode(bytes);
			if (stacks != null) return StorageEntries.of(stacks);
		}
		return null;
	}

	/** ItemStack[] as the addon's data library writes it: the length, then each stack, as Bukkit object streams. */
	private static @Nullable List<ItemStack> decode(byte[] bytes) {
		try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
			int length = in.readInt();
			List<ItemStack> stacks = new ArrayList<>(Math.max(0, Math.min(length, 54)));
			for (int i = 0; i < length; i++) stacks.add((ItemStack) in.readObject());
			return stacks;
		} catch (IOException | ClassNotFoundException | RuntimeException e) {
			return null;
		}
	}
}
