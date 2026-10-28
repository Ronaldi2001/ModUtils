package me.ronaldi2001.modutils.platform;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Everything the plugin needs from the server software that differs between Paper (and Folia) and Bukkit (Spigot,
 * CraftBukkit). Each platform module has one implementation; {@link #id()} is also the name of the platform module whose
 * version adapters (paper/v1_21_11, ...) it loads.
 */
public interface Platform {
	/** {@code paper} or {@code bukkit}. */
	String id();

	/** Called first, as the plugin turns on. */
	void init(JavaPlugin plugin);

	ModScheduler scheduler();

	/** The server's Minecraft version, e.g. {@code 1.21.8}. */
	String minecraftVersion();

	/** The language the player's client is set to. */
	Locale locale(Player player);

	/** Sends a message. Adventure components are native on Paper; Bukkit goes through adventure-platform-bukkit. */
	void send(CommandSender sender, Component message);

	/** Disconnects the player with the message as the reason. */
	void kick(Player player, Component reason);

	/** The brand the client sent when it joined ({@code fabric}, {@code vanilla}, ...), or null if not known (yet). */
	@Nullable String clientBrand(Player player);

	/**
	 * Calls back when the server reloads its data (the recipes change with it).
	 *
	 * @return an action that stops the calls
	 */
	Runnable onResourcesReloaded(Runnable callback);

	/** Registers {@code /modutils}, which anyone with {@link CommandActions#PERMISSION} may use. */
	void registerCommand(JavaPlugin plugin, CommandActions actions);

	/** Called when the plugin turns off. */
	default void close() {
	}
}
