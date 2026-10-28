package me.ronaldi2001.modutils.paper;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import me.ronaldi2001.modutils.platform.CommandActions;
import me.ronaldi2001.modutils.platform.ModScheduler;
import me.ronaldi2001.modutils.platform.Platform;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/** Paper, Folia and the forks of them. */
@SuppressWarnings("UnstableApiUsage")
public final class PaperPlatform implements Platform {
	private JavaPlugin plugin;
	private ModScheduler scheduler;

	@Override
	public void init(JavaPlugin plugin) {
		this.plugin = plugin;
		this.scheduler = new PaperScheduler(plugin);
	}

	@Override
	public String id() {
		return "paper";
	}

	@Override
	public ModScheduler scheduler() {
		return this.scheduler;
	}

	@Override
	public String minecraftVersion() {
		return Bukkit.getMinecraftVersion();
	}

	@Override
	public Locale locale(Player player) {
		return player.locale();
	}

	@Override
	public void send(CommandSender sender, Component message) {
		sender.sendMessage(message);
	}

	@Override
	public void kick(Player player, Component reason) {
		player.kick(reason);
	}

	@Override
	public @Nullable String clientBrand(Player player) {
		return player.getClientBrandName();
	}

	@Override
	public Runnable onResourcesReloaded(Runnable callback) {
		Listener listener = new ReloadListener(callback);
		this.plugin.getServer().getPluginManager().registerEvents(listener, this.plugin);
		return () -> HandlerList.unregisterAll(listener);
	}

	private record ReloadListener(Runnable callback) implements Listener {
		@EventHandler
		public void onReload(ServerResourcesReloadedEvent event) {
			this.callback.run();
		}
	}

	@Override
	public void registerCommand(JavaPlugin plugin, CommandActions actions) {
		plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
				event -> event.registrar().register(PaperCommands.build(plugin.getServer(), actions),
						"Turn Mod Utils features on and off, and reload them"));
	}
}
