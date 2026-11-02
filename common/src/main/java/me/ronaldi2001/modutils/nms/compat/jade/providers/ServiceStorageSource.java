package me.ronaldi2001.modutils.nms.compat.jade.providers;

import me.ronaldi2001.modutils.modCompat.api.StorageSource;
import me.ronaldi2001.modutils.nms.compat.jade.BlockCtx;
import me.ronaldi2001.modutils.nms.compat.jade.BlockItemSource;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows the contents of storages that other plugins registered as a {@link StorageSource} with Bukkit's
 * ServicesManager (RawHub's item storage barrels, for example).
 */
public final class ServiceStorageSource implements BlockItemSource {
	@Override
	public String configKey() {
		return "customItemStorage";
	}

	@Override
	public @Nullable List<ItemStack> itemsAt(BlockCtx ctx) {
		for (StorageSource source : sources()) {
			List<ItemStack> items = convert(source.itemsAt(ctx.bukkitBlock()));
			if (items != null) return items;
		}
		return null;
	}

	/** The contents of an entity that only exists on clients (CraftEngine furniture), or null if no source knows it. */
	public static @Nullable List<ItemStack> itemsOfEntity(World world, int entityId) {
		for (StorageSource source : sources()) {
			List<ItemStack> items = convert(source.itemsOfEntity(world, entityId));
			if (items != null) return items;
		}
		return null;
	}

	/** The contents of an entity a custom storage keeps its items for (storage furniture), or null. */
	public static @Nullable List<ItemStack> itemsOf(org.bukkit.entity.Entity entity) {
		for (StorageSource source : sources()) {
			List<ItemStack> items = convert(source.itemsOf(entity));
			if (items != null) return items;
		}
		return null;
	}

	/** The contents of a custom storage block, or null if no source handles it. */
	public static @Nullable List<ItemStack> itemsOfBlock(org.bukkit.block.Block block) {
		for (StorageSource source : sources()) {
			List<ItemStack> items = convert(source.itemsAt(block));
			if (items != null) return items;
		}
		return null;
	}

	private static List<StorageSource> sources() {
		return Bukkit.getServicesManager().getRegistrations(StorageSource.class).stream()
				.map(RegisteredServiceProvider::getProvider).toList();
	}

	private static @Nullable List<ItemStack> convert(@Nullable List<StorageSource.Entry> entries) {
		if (entries == null) return null;

		List<ItemStack> result = new ArrayList<>(entries.size());
		for (StorageSource.Entry entry : entries) {
			ItemStack stack = CraftItemStack.asNMSCopy(entry.template());
			stack.setCount(entry.amount());
			result.add(stack);
		}
		return result;
	}
}
