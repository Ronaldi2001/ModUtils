package me.ronaldi2001.modutils.translations;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TranslatableComponent;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Named arguments for {@link TransUtils#translatable}, used in a message as {@code <name>}. Stands in for Adventure's
 * own {@code Argument}, which older servers don't have.
 * <p>
 * A named argument travels as a translatable component with a reserved key, holding the value as its only argument;
 * the translation source in {@link TransUtils} unpacks it into a tag.
 */
public final class Argument {
	static final String KEY_PREFIX = "modutils.argument:";

	private Argument() {
	}

	public static @NotNull ComponentLike string(@NotNull String name, @NotNull String value) {
		return component(name, Component.text(value));
	}

	public static @NotNull ComponentLike numeric(@NotNull String name, @NotNull Number value) {
		return component(name, Component.text(value.toString()));
	}

	public static @NotNull ComponentLike component(@NotNull String name, @NotNull ComponentLike value) {
		return Component.translatable(KEY_PREFIX + name, value);
	}

	/** The name of a named argument, or empty if the component is a plain positional one. */
	static @NotNull Optional<String> nameOf(@NotNull Component component) {
		if (component instanceof TranslatableComponent translatable && translatable.key().startsWith(KEY_PREFIX)) {
			return Optional.of(translatable.key().substring(KEY_PREFIX.length()));
		}
		return Optional.empty();
	}
}
