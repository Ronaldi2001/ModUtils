package me.ronaldi2001.modutils.nms;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import org.jspecify.annotations.Nullable;

/**
 * The 26.x version of JukeboxNames: the song of a disc is a plain holder here.
 */
public final class JukeboxNames {
	private JukeboxNames() {
	}

	public static @Nullable Component songName(ItemStack stack, HolderLookup.Provider registries) {
		JukeboxPlayable playable = stack.get(DataComponents.JUKEBOX_PLAYABLE);
		if (playable == null) return null;

		return playable.song().value().description();
	}
}
