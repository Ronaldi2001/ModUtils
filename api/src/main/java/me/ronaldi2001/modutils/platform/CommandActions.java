package me.ronaldi2001.modutils.platform;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * What {@code /modutils} does, apart from how a server parses it: each platform turns its own command syntax into calls
 * on this.
 */
public interface CommandActions {
	String PERMISSION = "modutils.admin";

	void status(CommandSender sender);

	void players(CommandSender sender, List<? extends Player> players);

	void reload(CommandSender sender);

	void set(CommandSender sender, String path, String raw);

	/** Every setting that can be changed with {@code set}. */
	List<String> paths();

	/** Values worth suggesting for the setting at {@code path}. */
	List<String> valueSuggestions(String path);
}
