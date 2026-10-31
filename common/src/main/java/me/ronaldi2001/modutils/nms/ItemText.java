package me.ronaldi2001.modutils.nms;

import me.ronaldi2001.modutils.modCompat.api.ItemTextRenderer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders the text of items and blocks for one player's language: every {@link ItemTextRenderer} that plugins have
 * registered, then Adventure's global translator, which is what Paper itself does for items it sends.
 */
public final class ItemText {
	private ItemText() {
	}

	private static List<ItemTextRenderer> renderers() {
		java.util.Collection<RegisteredServiceProvider<ItemTextRenderer>> registrations =
				Bukkit.getServicesManager().getRegistrations(ItemTextRenderer.class);
		List<ItemTextRenderer> renderers = new ArrayList<>(registrations.size());
		for (RegisteredServiceProvider<ItemTextRenderer> registration : registrations) {
			renderers.add(registration.getProvider());
		}
		return renderers;
	}

	public static net.kyori.adventure.text.Component renderName(net.kyori.adventure.text.Component name, Locale locale) {
		net.kyori.adventure.text.Component rendered = name;
		for (ItemTextRenderer renderer : renderers()) rendered = renderer.renderName(rendered, locale);
		return GlobalTranslator.render(rendered, locale);
	}

	public static List<net.kyori.adventure.text.Component> renderLore(net.kyori.adventure.text.Component line, Locale locale) {
		List<net.kyori.adventure.text.Component> lines = List.of(line);
		for (ItemTextRenderer renderer : renderers()) {
			List<net.kyori.adventure.text.Component> next = new ArrayList<>(lines.size());
			for (net.kyori.adventure.text.Component current : lines) next.addAll(renderer.renderLore(current, locale));
			lines = next;
		}
		return lines.stream().map(l -> GlobalTranslator.render(l, locale)).toList();
	}

	public static Component renderName(Component name, Locale locale) {
		return AdventureBridge.asVanilla(renderName(AdventureBridge.asAdventure(name), locale));
	}

	public static List<Component> renderLore(Component line, Locale locale) {
		return renderLore(AdventureBridge.asAdventure(line), locale).stream().map(AdventureBridge::asVanilla).toList();
	}

	/**
	 * @return a copy of the stack with its name and lore rendered, or the stack itself if it has neither
	 */
	public static ItemStack render(ItemStack stack, Locale locale) {
		if (stack.isEmpty()) return stack;

		boolean hasName = Nms.hasNonDefault(stack, DataComponents.ITEM_NAME);
		Component customName = stack.get(DataComponents.CUSTOM_NAME);
		ItemLore lore = stack.get(DataComponents.LORE);
		boolean hasLore = lore != null && !lore.lines().isEmpty();
		if (!hasName && customName == null && !hasLore) return stack;

		ItemStack copy = stack.copy();
		if (hasName) copy.set(DataComponents.ITEM_NAME, renderName(stack.get(DataComponents.ITEM_NAME), locale));
		if (customName != null) copy.set(DataComponents.CUSTOM_NAME, renderName(customName, locale));
		if (hasLore) {
			List<Component> lines = new ArrayList<>();
			for (Component line : lore.lines()) lines.addAll(renderLore(line, locale));
			copy.set(DataComponents.LORE, new ItemLore(lines.subList(0, Math.min(lines.size(), ItemLore.MAX_LINES))));
		}
		return copy;
	}
}
