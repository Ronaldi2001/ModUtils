package me.ronaldi2001.modutils.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.ronaldi2001.modutils.platform.CommandActions;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /modutils} as a Brigadier tree: the client suggests and checks it as it is typed.
 * <ul>
 *   <li>{@code status}</li>
 *   <li>{@code players [name]}</li>
 *   <li>{@code reload}</li>
 *   <li>{@code set <path> <value>}</li>
 * </ul>
 */
final class PaperCommands {
	private PaperCommands() {
	}

	static LiteralCommandNode<CommandSourceStack> build(Server server, CommandActions actions) {
		return Commands.literal("modutils")
				.requires(source -> source.getSender().hasPermission(CommandActions.PERMISSION))
				.then(Commands.literal("status").executes(context -> {
					actions.status(context.getSource().getSender());
					return Command.SINGLE_SUCCESS;
				}))
				.then(Commands.literal("players")
						.executes(context -> {
							actions.players(context.getSource().getSender(), List.copyOf(server.getOnlinePlayers()));
							return Command.SINGLE_SUCCESS;
						})
						.then(Commands.argument("player", ArgumentTypes.player()).executes(context -> {
							List<Player> players = context.getArgument("player", PlayerSelectorArgumentResolver.class).resolve(context.getSource());
							actions.players(context.getSource().getSender(), players);
							return Command.SINGLE_SUCCESS;
						})))
				.then(Commands.literal("reload").executes(context -> {
					actions.reload(context.getSource().getSender());
					return Command.SINGLE_SUCCESS;
				}))
				.then(Commands.literal("set")
						.then(Commands.argument("path", StringArgumentType.word())
								.suggests((context, builder) -> {
									String typed = builder.getRemaining().toLowerCase();
									actions.paths().stream().filter(path -> path.toLowerCase().startsWith(typed)).forEach(builder::suggest);
									return builder.buildFuture();
								})
								.then(Commands.argument("value", StringArgumentType.greedyString())
										.suggests((context, builder) -> {
											actions.valueSuggestions(context.getArgument("path", String.class)).forEach(builder::suggest);
											return builder.buildFuture();
										})
										.executes(context -> {
											actions.set(context.getSource().getSender(), context.getArgument("path", String.class),
													context.getArgument("value", String.class).strip());
											return Command.SINGLE_SUCCESS;
										}))))
				.build();
	}
}
