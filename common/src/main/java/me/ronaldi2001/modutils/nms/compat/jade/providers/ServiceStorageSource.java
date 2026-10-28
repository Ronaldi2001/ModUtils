package me.ronaldi2001.modutils.nms.compat.jade.providers;

import me.ronaldi2001.modutils.modCompat.api.StorageSource;
import me.ronaldi2001.modutils.nms.compat.jade.BlockCtx;
import me.ronaldi2001.modutils.nms.compat.jade.BlockItemSource;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
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
		for (StorageSource source : Bukkit.getServicesManager().getRegistrations(StorageSource.class).stream()
				.map(RegisteredServiceProvider::getProvider).toList()) {
			List<StorageSource.Entry> entries = source.itemsAt(ctx.bukkitBlock());
			if (entries == null) continue;

			List<ItemStack> result = new ArrayList<>(entries.size());
			for (StorageSource.Entry entry : entries) {
				ItemStack stack = CraftItemStack.asNMSCopy(entry.template());
				stack.setCount(entry.amount());
				result.add(stack);
			}
			return result;
		}
		return null;
	}
}
