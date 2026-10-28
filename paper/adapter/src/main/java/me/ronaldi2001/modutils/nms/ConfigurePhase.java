package me.ronaldi2001.modutils.nms;

import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * Gets a compat to see every connection while it is still in its configuration phase, before the player exists.
 * Paper tells plugins about those from 1.21.8 on; the older versions have a ConfigurePhase of
 * their own (in paper/<version>) that never calls back, and the compats then go by the join event instead (see {@link #available}).
 */
public final class ConfigurePhase {
	private ConfigurePhase() {
	}

	public interface Handler {
		/**
		 * @param id         the player's UUID
		 * @param connection Paper's connection object, for {@code RecipeUpdateHold.channelOf}
		 */
		void onConfigure(UUID id, String name, Object connection);
	}

	/**
	 * @return whether connections are seen in their configuration phase on this version
	 */
	public static boolean available() {
		return true;
	}

	/**
	 * @return an action that stops the handler from being called again
	 */
	public static Runnable register(ModUtilsPlugin plugin, Handler handler) {
		Hook hook = new Hook(handler);
		plugin.getServer().getPluginManager().registerEvents(hook, plugin);
		return () -> HandlerList.unregisterAll(hook);
	}

	private record Hook(Handler handler) implements Listener {
		@SuppressWarnings("UnstableApiUsage")
		@EventHandler
		public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
			UUID id = event.getConnection().getProfile().getId();
			if (id == null) return;
			this.handler.onConfigure(id, String.valueOf(event.getConnection().getProfile().getName()), event.getConnection());
		}
	}
}
