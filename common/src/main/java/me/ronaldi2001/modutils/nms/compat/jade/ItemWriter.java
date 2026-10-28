package me.ronaldi2001.modutils.nms.compat.jade;

import me.ronaldi2001.modutils.nms.ItemTranslator;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

/**
 * Writes an item stack in the form the receiving client can read.
 */
@FunctionalInterface
public interface ItemWriter {
	/**
	 * Same version as the server: use the vanilla codec as is.
	 */
	ItemWriter VANILLA = ItemStack.OPTIONAL_STREAM_CODEC::encode;

	void write(RegistryFriendlyByteBuf buf, ItemStack stack);

	/**
	 * For a client on another version: remaps ids and keeps only what can be translated.
	 */
	static ItemWriter translating(ItemTranslator translator) {
		return translator::writeStack;
	}
}
