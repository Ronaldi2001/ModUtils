package me.ronaldi2001.modutils.bukkit;

import me.ronaldi2001.modutils.platform.CommandActions;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * {@code /modutils} the way Bukkit parses commands: one string, split at spaces. Same words as on Paper
 * ({@code status}, {@code players [name]}, {@code reload}, {@code set <path> <value>}), without the target selectors.
 */
final class BukkitCommand implements TabExecutor {
	private static final List<String> SUBCOMMANDS = List.of("status", "players", "reload", "set");

	private final Server server;
	private final CommandActions actions;

	BukkitCommand(Server server, CommandActions actions) {
		this.server = server;
		this.actions = actions;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission(CommandActions.PERMISSION)) return false;
		if (args.length == 0) return false;

		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "status" -> this.actions.status(sender);
			case "reload" -> this.actions.reload(sender);
			case "players" -> {
				if (args.length == 1) {
					this.actions.players(sender, List.copyOf(this.server.getOnlinePlayers()));
					return true;
				}
				Player player = this.server.getPlayerExact(args[1]);
				if (player == null) {
					sender.sendMessage("No player named " + args[1] + " is online");
					return true;
				}
				this.actions.players(sender, List.of(player));
			}
			case "set" -> {
				if (args.length < 3) return false;
				this.actions.set(sender, args[1], String.join(" ", List.of(args).subList(2, args.length)).strip());
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission(CommandActions.PERMISSION)) return List.of();
		if (args.length == 1) return startingWith(SUBCOMMANDS, args[0]);

		String sub = args[0].toLowerCase(Locale.ROOT);
		if (sub.equals("players") && args.length == 2) {
			return startingWith(this.server.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
		}
		if (sub.equals("set")) {
			if (args.length == 2) return startingWith(this.actions.paths(), args[1]);
			if (args.length == 3) return startingWith(this.actions.valueSuggestions(args[1]), args[2]);
		}
		return List.of();
	}

	private static List<String> startingWith(List<String> options, String typed) {
		String lower = typed.toLowerCase(Locale.ROOT);
		return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
	}
}
