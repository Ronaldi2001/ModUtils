package me.ronaldi2001.modutils.nms;

import io.papermc.paper.adventure.PaperAdventure;

/**
 * Converts between Adventure's components and Minecraft's. Paper has a converter of its own; Bukkit servers do not, and
 * have an AdventureBridge of their own (bukkit/adapter) that goes through JSON instead.
 */
@SuppressWarnings("unused") // the shared code that calls this is only compiled into the merged adapter
public final class AdventureBridge {
	private AdventureBridge() {
	}

	public static net.minecraft.network.chat.Component asVanilla(net.kyori.adventure.text.Component component) {
		return PaperAdventure.asVanilla(component);
	}

	public static net.kyori.adventure.text.Component asAdventure(net.minecraft.network.chat.Component component) {
		return PaperAdventure.asAdventure(component);
	}
}
