package me.ronaldi2001.modutils.modCompat;

import me.ronaldi2001.modutils.FeatureModule;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.adapter.VersionAdapter;
import me.ronaldi2001.modutils.modCompat.api.ModCompat;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Server-side support for client mods (JEI, Jade, AppleSkin, Polymer, TOP). The Minecraft-version specific parts live in
 * adapters; this class only holds the compats the adapter makes and switches them on and off.
 */
public final class ModCompatModule implements FeatureModule {
	public static final String CONFIG_KEY = "modCompat";

	private final ModUtilsPlugin plugin;
	/** Every compat this version has, switched on or not. */
	private final List<ModCompat> compats = new ArrayList<>();
	private final List<ModCompat> active = new ArrayList<>();

	private ModCompatModule(ModUtilsPlugin plugin) {
		this.plugin = plugin;
	}

	/** Creates the compats of the adapter's Minecraft version. */
	public static ModCompatModule create(ModUtilsPlugin plugin, VersionAdapter adapter) {
		ModCompatModule module = new ModCompatModule(plugin);
		module.compats.addAll(adapter.createCompats(plugin));
		return module;
	}

	@Override
	public String getConfigKey() {
		return CONFIG_KEY;
	}

	/** Every compat of this Minecraft version, whether it is switched on or not. */
	public List<ModCompat> getCompats() {
		return List.copyOf(this.compats);
	}

	public boolean isActive(ModCompat compat) {
		return this.active.contains(compat);
	}

	/** Whether any compat is running. */
	@Override
	public boolean isActive() {
		return !this.active.isEmpty();
	}

	/** The full config path of a compat's section, e.g. {@code modCompat.jade}. */
	public static String getConfigPath(ModCompat compat) {
		return CONFIG_KEY + "." + compat.getConfigKey();
	}

	/** The compat that a config path (its section or a setting inside it) belongs to, or null. */
	public @Nullable ModCompat compatFor(String path) {
		for (ModCompat compat : this.compats) {
			String section = getConfigPath(compat);
			if (path.equals(section) || path.startsWith(section + ".")) return compat;
		}
		return null;
	}

	/** Switches every compat on whose section in the config says so. */
	@Override
	public boolean start() {
		this.compats.forEach(this::enable);
		return this.isActive();
	}

	@Override
	public void stop() {
		for (ModCompat compat : List.copyOf(this.active).reversed()) this.disable(compat);
	}

	/** Restarts only the compat the setting belongs to, or all of them if it belongs to none. */
	@Override
	public boolean restart(String path) {
		ModCompat compat = this.compatFor(path);
		if (compat == null) return FeatureModule.super.restart(path);
		return this.restart(compat);
	}

	/**
	 * Applies the config as it is now to one compat: off if its section says so, otherwise started afresh.
	 *
	 * @return whether it is on now
	 */
	public boolean restart(ModCompat compat) {
		this.disable(compat);
		return this.enable(compat);
	}

	/**
	 * Switches a compat on if its section in the config says so.
	 *
	 * @return whether it is on now
	 */
	private boolean enable(ModCompat compat) {
		ConfigurationSection section = this.plugin.getSection(getConfigPath(compat));
		if (section == null || !section.getBoolean("enabled", false)) return false;

		try {
			compat.onEnable(section);
			this.plugin.getServer().getPluginManager().registerEvents(compat, this.plugin);
			this.active.add(compat);
			this.plugin.getLogger().info("Mod compat enabled: " + compat.getName());
			return true;
		} catch (Throwable e) {
			this.plugin.getLogger().log(Level.SEVERE, "Failed to enable mod compat: " + compat.getName(), e);
			this.cleanUp(compat);
			return false;
		}
	}

	private void disable(ModCompat compat) {
		if (!this.active.remove(compat)) return;

		this.cleanUp(compat);
		this.plugin.getLogger().info("Mod compat disabled: " + compat.getName());
	}

	/** Takes the compat's listeners away and lets it clean up after itself. */
	private void cleanUp(ModCompat compat) {
		HandlerList.unregisterAll(compat);
		try {
			compat.onDisable();
		} catch (Throwable e) {
			this.plugin.getLogger().log(Level.SEVERE, "Failed to disable mod compat: " + compat.getName(), e);
		}
	}
}
