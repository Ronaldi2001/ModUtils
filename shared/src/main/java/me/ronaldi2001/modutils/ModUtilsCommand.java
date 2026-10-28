package me.ronaldi2001.modutils;

import me.ronaldi2001.modutils.modCompat.api.ModCompat;
import me.ronaldi2001.modutils.modBlock.ModBlockModule;
import me.ronaldi2001.modutils.modCompat.ModCompatModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import me.ronaldi2001.modutils.pluginHooks.via.ViaVersionHook;
import me.ronaldi2001.modutils.translations.Argument;
import me.ronaldi2001.modutils.platform.CommandActions;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import me.ronaldi2001.modutils.translations.TransUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * {@code /modutils}: turns features on and off and debugging on and off without a restart, and reloads the config.
 * <ul>
 *   <li>{@code status}: which compats are on</li>
 *   <li>{@code players [name]}: everyone online, or just that player, and which client mods each has (green if its compat is on, red if it is off)</li>
 *   <li>{@code reload}: read config.yml again, restart every feature and show the status</li>
 *   <li>{@code set <path> <value>}: change one setting (e.g. {@code jade.features.furnace false},
 *   {@code jei.debug true}, {@code appleSkin.enabled false}), save it to config.yml (only that line, comments are left
 *   alone) and restart the compat it belongs to.</li>
 * </ul>
 */
public final class ModUtilsCommand implements CommandActions {
	/** The messages of the command are in the lang file under this section. */
	private static final String LANG = ModCompatModule.CONFIG_KEY + ".";

	private final ModUtilsPlugin plugin;

	public ModUtilsCommand(ModUtilsPlugin plugin) {
		this.plugin = plugin;
	}

	private static TranslatableComponent lang(String key, ComponentLike... args) {
		return TransUtils.translatable(LANG + key, args);
	}

	/** The status messages are in the lang file under {@code status}. */
	private static TranslatableComponent status(String key, ComponentLike... args) {
		return TransUtils.translatable("status." + key, args);
	}

	/** Sends the lines as one message, so a plugin that reads or blocks chat only has one to look at. */
	private void send(CommandSender sender, List<? extends ComponentLike> lines) {
		this.plugin.getPlatform().send(sender, Component.join(JoinConfiguration.newlines(), lines));
	}

	/** On or off, colored. */
	private Component state(boolean on) {
		return status(on ? "compat.state.enabled" : "compat.state.disabled");
	}

	@Override
	public void status(CommandSender sender) {
		this.plugin.getPlatform().send(sender, this.statusMessage());
	}

	private Component statusMessage() {
		FileConfiguration config = this.plugin.getConfig();
		List<ComponentLike> lines = new ArrayList<>();
		lines.add(status("header"));

		String[] versions = this.plugin.getSupportedVersions();
		lines.add(status("compat.title", Argument.component("versions", versions[0].equals(versions[1])
				? Component.text(versions[0])
				: status("compat.range", Argument.string("min", versions[0]), Argument.string("max", versions[1])))));
		if (this.compats().isEmpty()) lines.add(status("compat.none"));
		for (ModCompat compat : this.compats()) {
			String key = ModCompatModule.getConfigPath(compat);
			boolean on = this.isActive(compat);
			boolean configured = config.getBoolean(key + ".enabled", false);
			boolean debug = on && config.getBoolean(key + ".debug", false);
			lines.add(status("compat.line",
					Argument.component("mod_name", this.displayName(compat)),
					Argument.component("state", this.state(on)),
					Argument.component("debug", debug ? status("debug") : Component.empty()),
					Argument.component("failed", !on && configured ? status("failed") : Component.empty())));
		}

		ModBlockModule modBlock = this.plugin.getModBlock();
		lines.add(status("block.title", Argument.component("debug", modBlock != null && modBlock.isDebug() ? status("debug") : Component.empty())));
		Set<String> blocked = modBlock == null || !modBlock.isActive() ? Set.of() : modBlock.getBlockedMods();
		if (modBlock == null || !modBlock.isActive()) lines.add(status("block.disabled"));
		else if (blocked.isEmpty()) lines.add(status("block.none"));
		for (String id : blocked) {
			lines.add(status("block.line", Argument.string("mod_name", this.plugin.getModName(id))));
		}

		lines.add(status("hooks.title"));
		List<String> missing = new ArrayList<>();
		this.plugin.getHooks().forEach((name, hooked) -> {
			if (!hooked) {
				missing.add(name);
				return;
			}
			lines.add(status("hooks.hooked", Argument.string("hook_name", name)));
		});
		if (!missing.isEmpty()) {
			List<ComponentLike> hover = new ArrayList<>();
			hover.add(status("hooks.missing.title"));
			for (String name : missing) hover.add(status("hooks.missing.line", Argument.string("hook_name", name)));
			lines.add(status("hooks.missing.button", Argument.numeric("count", missing.size()))
					.hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(), hover))));
		}
		return Component.join(JoinConfiguration.newlines(), lines);
	}

	@Override
	public void players(CommandSender sender, List<? extends Player> players) {
		List<ComponentLike> lines = new ArrayList<>();
		lines.add(players.size() == 1
				? lang("players.header.single")
				: lang("players.header.many", Argument.numeric("player_count", players.size())));
		for (Player player : players) {
			Component detail;
			if (this.plugin.getBedrockHook().isBedrock(player)) {
				detail = lang("players.bedrock");
			} else {
				List<Component> mods = new ArrayList<>();
				for (ModCompat compat : this.compats()) {
					if (compat.detect(player)) mods.add(this.modName(compat));
				}
				detail = mods.isEmpty() ? lang("players.none")
						: Component.join(JoinConfiguration.separator(lang("players.separator")), mods);
			}
			lines.add(lang("players.line",
					Argument.string("player", player.getName()),
					Argument.component("client", this.clientInfo(player)),
					Argument.component("mods", detail)));
		}
		send(sender, lines);
	}

	/** The mod's name from the {@code mods} section of the config. */
	private Component displayName(ModCompat compat) {
		return Component.text(this.plugin.getModName(compat.getConfigKey()));
	}

	/** The mod's name, colored by whether its compat is on (see {@code modCompat.players.usage}). */
	private Component modName(ModCompat compat) {
		return lang(this.isActive(compat) ? "players.usage.using" : "players.usage.notUsing", Argument.component("mod_name", this.displayName(compat)));
	}

	/** The client's brand and version, e.g. {@code  [fabric 1.21.8]}, or nothing if neither is known. */
	private Component clientInfo(Player player) {
		String brand = this.plugin.getPlatform().clientBrand(player);
		ViaVersionHook via = this.plugin.getViaVersionHook();
		String version = via == null ? null : via.getPlayerVersionName(player);
		if (brand == null && version == null) return Component.empty();

		return lang("players.client",
				Argument.string("players_client", brand == null ? "" : brand),
				Argument.string("players_version", version == null ? "" : version));
	}

	@Override
	public void reload(CommandSender sender) {
		this.plugin.reloadAll();
		status(sender);
	}

	@Override
	public void set(CommandSender sender, String path, String raw) {
		FileConfiguration config = this.plugin.getConfig();
		Object current = config.get(path);
		if (current == null || config.isConfigurationSection(path)) {
			this.plugin.getPlatform().send(sender, lang("set.unknown", Argument.string("path", path)));
			return;
		}

		Object value = parse(current, raw);
		if (value == null) {
			this.plugin.getPlatform().send(sender, lang("set.invalid",
					Argument.string("value", raw),
					Argument.string("path", path),
					Argument.component("current", current instanceof List<?>
							? lang("set.list")
							: Component.text(String.valueOf(current)))));
			return;
		}

		config.set(path, value);
		Component saved = this.save(path, value);

		FeatureModule module = this.plugin.moduleFor(path);
		if (module == null) {
			this.plugin.getPlatform().send(sender, lang("set.unused",
					Argument.string("path", path), Argument.string("value", String.valueOf(value)),
					Argument.component("saved", saved)));
			return;
		}

		ModCompatModule compatModule = this.plugin.getModCompat();
		ModCompat compat = compatModule == null ? null : compatModule.compatFor(path);
		boolean on = module.restart(path);
		this.plugin.getPlatform().send(sender, lang("set.restarted",
				Argument.string("path", path), Argument.string("value", String.valueOf(value)),
				Argument.component("feature_name", compat == null ? Component.text(module.getConfigKey()) : this.displayName(compat)),
				Argument.component("state", this.state(on)),
				Argument.component("saved", saved)));
	}

	/**
	 * Writes the value into config.yml.
	 *
	 * @return what to tell the sender about it
	 */
	private Component save(String path, Object value) {
		try {
			if (ConfigFile.set(this.plugin.getDataFolder().toPath().resolve("config.yml"), path, value)) return lang("set.saved");
			return lang("set.no_line");
		} catch (IOException e) {
			this.plugin.getLogger().log(Level.WARNING, "Could not save " + path + " to config.yml", e);
			return lang("set.write_failed");
		}
	}

	/**
	 * @return the value in the same kind as the current one, or null if the text doesn't fit (or the current value can't
	 * be changed from here)
	 */
	private static @Nullable Object parse(Object current, String raw) {
		try {
			if (current instanceof Boolean) {
				if (raw.equalsIgnoreCase("true")) return true;
				if (raw.equalsIgnoreCase("false")) return false;
				return null;
			}
			if (current instanceof Integer) return Integer.parseInt(raw);
			if (current instanceof Long) return Long.parseLong(raw);
			if (current instanceof Number) return Double.parseDouble(raw);
			if (current instanceof List<?>) return null;
			return raw;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private boolean isActive(ModCompat compat) {
		ModCompatModule module = this.plugin.getModCompat();
		return module != null && module.isActive(compat);
	}

	private List<ModCompat> compats() {
		ModCompatModule module = this.plugin.getModCompat();
		return module == null ? List.of() : module.getCompats();
	}

	/** Every setting that can be changed here: all the values, not the sections or lists. */
	@Override
	public List<String> paths() {
		FileConfiguration config = this.plugin.getConfig();
		return config.getKeys(true).stream()
				.filter(path -> !config.isConfigurationSection(path) && !(config.get(path) instanceof List<?>))
				.toList();
	}

	@Override
	public List<String> valueSuggestions(String path) {
		Object current = this.plugin.getConfig().get(path);
		if (current instanceof Boolean) return List.of("true", "false");
		return current == null ? List.of() : List.of(String.valueOf(current));
	}
}
