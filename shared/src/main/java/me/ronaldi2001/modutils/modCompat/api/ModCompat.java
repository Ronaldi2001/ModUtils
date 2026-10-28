package me.ronaldi2001.modutils.modCompat.api;

import me.ronaldi2001.modutils.ModUtilsPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

/**
 * A server-side compatibility layer for a client mod. Each compat registers its own plugin
 * channels and listeners and is toggled through {@code modCompat.<configKey>.enabled} in the config.
 */
public abstract class ModCompat implements Listener {
	protected final ModUtilsPlugin plugin;

	protected ModCompat(ModUtilsPlugin plugin) {
		this.plugin = plugin;
	}

	/** Key in the config, inside the {@code modCompat} section. */
	public abstract String getConfigKey();

	public abstract String getName();

	public void onEnable(ConfigurationSection section) {
	}

	public void onDisable() {
	}

	/**
	 * Whether this player's client has the mod, for {@code /modcompat players}. Works whether or not the compat is
	 * switched on.
	 */
	public boolean detect(Player player) {
		return false;
	}
}
