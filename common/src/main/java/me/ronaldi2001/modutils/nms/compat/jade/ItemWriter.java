package me.ronaldi2001.modutils.nms.compat.jade;

import me.ronaldi2001.modutils.nms.ItemTranslator;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import me.ronaldi2001.modutils.nms.ItemText;

import java.util.Locale;

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
	 * Same version as the server, with the item's name and lore rendered for the player's language first.
	 */
	static ItemWriter rendering(Locale locale) {
		return (buf, stack) -> ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, ItemText.render(stack, locale));
	}

	/**
	 * For a client on another version: remaps ids and keeps only what can be translated.
	 */
	static ItemWriter translating(ItemTranslator translator) {
		return translator::writeStack;
	}
}
