package me.ronaldi2001.modutils.nms;

import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The 1.20.6 version of JukeboxNames: music discs have no song component yet, so there is no title to find.
 */
@SuppressWarnings("unused") // the shared code that calls this is only compiled into the merged adapter
public final class JukeboxNames {
	private JukeboxNames() {
	}

	public static @Nullable Component songName(ItemStack stack, HolderLookup.Provider registries) {
		return null;
	}
}
