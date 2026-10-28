package me.ronaldi2001.modutils.nms.compat.jade;

import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A custom storage whose items aren't in a vanilla container (for example a block that keeps its contents in
 * plugin data). Consulted before the vanilla container lookup in the item storage tooltip.
 */
public interface BlockItemSource {
	/** Config key under {@code modCompat.jade.features} that toggles this source. */
	String configKey();

	/**
	 * @return the items to show instead of the block's own container, or null if this source doesn't handle the
	 * block. An empty list means it does, but there is nothing inside.
	 */
	default @Nullable List<ItemStack> itemsAt(BlockCtx ctx) {
		return null;
	}
}
