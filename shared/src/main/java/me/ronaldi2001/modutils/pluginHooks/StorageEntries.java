package me.ronaldi2001.modutils.pluginHooks;

import me.ronaldi2001.modutils.modCompat.api.StorageSource;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Turns the stacks a plugin's storage holds into {@link StorageSource} entries. */
public final class StorageEntries {
	private StorageEntries() {
	}

	/** Merges identical stacks, as Jade does for a vanilla container; empty slots may be null. */
	public static List<StorageSource.Entry> of(Iterable<@Nullable ItemStack> stacks) {
		List<StorageSource.Entry> entries = new ArrayList<>();
		for (ItemStack stack : stacks) {
			if (stack == null || stack.isEmpty()) continue;
			int index = -1;
			for (int i = 0; i < entries.size(); i++) {
				if (entries.get(i).template().isSimilar(stack)) index = i;
			}
			if (index < 0) {
				entries.add(new StorageSource.Entry(stack.asOne(), stack.getAmount()));
			} else {
				StorageSource.Entry known = entries.get(index);
				entries.set(index, new StorageSource.Entry(known.template(), known.amount() + stack.getAmount()));
			}
		}
		return entries;
	}
}
