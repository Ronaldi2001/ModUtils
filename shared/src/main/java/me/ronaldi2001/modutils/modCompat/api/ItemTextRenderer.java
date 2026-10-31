package me.ronaldi2001.modutils.modCompat.api;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Locale;

/**
 * Turns the text of a plugin's own items and blocks into what a player should read. Items and blocks that Mod Utils
 * writes for a client mod (Jade, The One Probe, JEI) skip Paper's own per-player rendering, so text that only the
 * owning plugin can resolve, such as a translation key with a custom prefix, would show up as it is.
 * <p>
 * Any number of plugins can register one at the same time, each for its own items and blocks. Register with Bukkit's
 * ServicesManager:
 * <pre>
 * Bukkit.getServicesManager().register(ItemTextRenderer.class, renderer, plugin, ServicePriority.Normal);
 * </pre>
 * Every registered renderer is applied to every piece of text, highest priority first, each one getting the result of
 * the one before. A renderer must return what it is given for anything that isn't its own. Adventure's global
 * translator is applied last, so ordinary translatable components are still handled.
 * <p>
 * Called on whichever thread is building the packet, so it must not touch the world and has to be thread safe.
 */
public interface ItemTextRenderer {
	/**
	 * Renders an item name, a custom block name or an entity name.
	 *
	 * @return the text for this language, or {@code name} itself if it isn't this plugin's
	 */
	Component renderName(Component name, Locale locale);

	/**
	 * Renders one line of an item's lore.
	 *
	 * @return the lines to show instead: more than one if the text contains line breaks, or just {@code line} if it
	 * isn't this plugin's
	 */
	default List<Component> renderLore(Component line, Locale locale) {
		return List.of(this.renderName(line, locale));
	}
}
