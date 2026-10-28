package me.ronaldi2001.modutils.nms;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import org.jspecify.annotations.Nullable;

/**
 * The title of the song a music disc plays. The song is a reference to a registry entry in 1.21.x (this version, used by
 * every version up to 1.21.11), a plain holder in 26.x and there is none in 1.20.6: those adapters have their own copy
 * of this file (same path, in their module's src/main/java).
 */
@SuppressWarnings("unused") // the shared code that calls this is only compiled into the merged adapter
public final class JukeboxNames {
	private JukeboxNames() {
	}

	/**
	 * @return the description of the song the stack plays (what a datapack or plugin set for a custom disc), or null if the stack
	 * isn't a disc or its song isn't registered
	 */
	public static @Nullable Component songName(ItemStack stack, HolderLookup.Provider registries) {
		JukeboxPlayable playable = stack.get(DataComponents.JUKEBOX_PLAYABLE);
		if (playable == null) return null;

		return playable.song().unwrap(registries).map(song -> song.value().description()).orElse(null);
	}
}
