package me.ronaldi2001.modutils;

import me.ronaldi2001.modutils.adapter.AdapterLoader;
import me.ronaldi2001.modutils.adapter.VersionAdapter;
import me.ronaldi2001.modutils.modBlock.ModBlockModule;
import me.ronaldi2001.modutils.modCompat.ModCompatModule;
import me.ronaldi2001.modutils.pluginHooks.bedrock.BedrockHook;
import me.ronaldi2001.modutils.pluginHooks.CustomItemSource;
import me.ronaldi2001.modutils.pluginHooks.craftengine.CraftEngineHook;
import me.ronaldi2001.modutils.pluginHooks.itemsadder.ItemsAdderHook;
import me.ronaldi2001.modutils.pluginHooks.nexo.NexoHook;
import me.ronaldi2001.modutils.pluginHooks.oraxen.OraxenHook;
import me.ronaldi2001.modutils.pluginHooks.via.ViaVersionHook;
import me.ronaldi2001.modutils.platform.ModScheduler;
import me.ronaldi2001.modutils.platform.Platform;
import me.ronaldi2001.modutils.translations.LangFileMerger;
import me.ronaldi2001.modutils.translations.TransUtils;
import me.ronaldi2001.modutils.updates.UpdateCheckerModule;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Mod Utils: server-side tools for client mods. The plugin only holds what the features share (config, translations,
 * hooks) and starts and stops its {@link FeatureModule}s: mod compat, mod block and the update checker.
 */
public final class ModUtilsPlugin extends JavaPlugin {
	public static final String PREFIX = "modutils";

	private final List<FeatureModule> modules = new ArrayList<>();
	private Platform platform;
	private @Nullable ModCompatModule modCompat;
	private @Nullable ModBlockModule modBlock;
	private UpdateCheckerModule updateChecker;
	private @Nullable ViaVersionHook viaVersionHook;
	private BedrockHook bedrockHook;
	private @Nullable CraftEngineHook craftEngineHook;
	private @Nullable ItemsAdderHook itemsAdderHook;
	private @Nullable NexoHook nexoHook;
	private @Nullable OraxenHook oraxenHook;

	/** The server software this runs on: Paper (and Folia) or plain Bukkit. */
	public Platform getPlatform() {
		return this.platform;
	}

	/** Runs code on the thread the server wants it on (see {@link ModScheduler}). */
	public ModScheduler getScheduler() {
		return this.platform.scheduler();
	}

	/** The ViaVersion hook, or null when ViaVersion isn't installed. */
	public @Nullable ViaVersionHook getViaVersionHook() {
		return this.viaVersionHook;
	}

	/** The CraftEngine hook, or null when CraftEngine isn't installed. */
	public @Nullable CraftEngineHook getCraftEngineHook() {
		return this.craftEngineHook;
	}

	/** Every installed plugin that has custom items by id (CraftEngine, ItemsAdder, Nexo, Oraxen). */
	public List<CustomItemSource> getItemSources() {
		List<CustomItemSource> sources = new ArrayList<>();
		if (this.craftEngineHook != null) sources.add(this.craftEngineHook);
		if (this.itemsAdderHook != null) sources.add(this.itemsAdderHook);
		if (this.nexoHook != null) sources.add(this.nexoHook);
		if (this.oraxenHook != null) sources.add(this.oraxenHook);
		return sources;
	}

	/**
	 * A custom item by id. An id that starts with a plugin's prefix ({@code nexo:amethyst_sword},
	 * {@code oraxen:ruby}, {@code itemsadder:ns:id}, {@code craftengine:default:topaz}) goes to that plugin only, with the
	 * prefix taken off. Without a prefix every installed plugin is asked in turn.
	 *
	 * @return the item, or null if no plugin has it
	 */
	public @Nullable ItemStack getCustomItem(String id) {
		List<CustomItemSource> sources = this.getItemSources();
		for (CustomItemSource source : sources) {
			String prefix = source.prefix() + ":";
			if (id.regionMatches(true, 0, prefix, 0, prefix.length())) return source.itemStack(id.substring(prefix.length()));
		}
		for (CustomItemSource source : sources) {
			ItemStack item = source.itemStack(id);
			if (item != null) return item;
		}
		return null;
	}

	/** Tells Bedrock players (Geyser, Floodgate) apart. Always there, and says no to everyone when neither is installed. */
	public BedrockHook getBedrockHook() {
		return this.bedrockHook;
	}

	/** Every feature this server runs, switched on or not. */
	public List<FeatureModule> getModules() {
		return List.copyOf(this.modules);
	}

	/** The mod compat feature, or null when this Minecraft version has no adapter. */
	public @Nullable ModCompatModule getModCompat() {
		return this.modCompat;
	}

	/** The mod block feature, or null when this Minecraft version has no adapter. */
	public @Nullable ModBlockModule getModBlock() {
		return this.modBlock;
	}

	/**
	 * The oldest and newest Minecraft versions players can join with: the server's own, unless ViaVersion lets in
	 * others (and then within the versions this plugin has adapters for).
	 *
	 * @return {oldest, newest}
	 */
	public String[] getSupportedVersions() {
		if (this.viaVersionHook == null) {
			String version = this.platform.minecraftVersion();
			return new String[]{version, version};
		}
		return this.viaVersionHook.getSupportedRange(AdapterLoader.oldestSupported(), AdapterLoader.newestSupported());
	}

	/** The plugins this one hooks into, by name, and whether each was found. */
	public Map<String, Boolean> getHooks() {
		Map<String, Boolean> hooks = new LinkedHashMap<>();
		hooks.put("ViaVersion", this.viaVersionHook != null);
		hooks.put("CraftEngine", this.craftEngineHook != null);
		hooks.put("ItemsAdder", this.itemsAdderHook != null);
		hooks.put("Nexo", this.nexoHook != null);
		hooks.put("Oraxen", this.oraxenHook != null);
		hooks.put("Geyser", this.bedrockHook.isGeyserHooked());
		hooks.put("Floodgate", this.bedrockHook.isFloodgateHooked());
		return hooks;
	}

	/** The feature that a full config path (e.g. {@code modCompat.jade.enabled}) belongs to, or null. */
	public @Nullable FeatureModule moduleFor(String path) {
		for (FeatureModule module : this.modules) {
			String key = module.getConfigKey();
			if (path.equals(key) || path.startsWith(key + ".")) return module;
		}
		return null;
	}

	/**
	 * A section of the config. A config.yml from before the section existed has none, and Bukkit then hands out an empty
	 * one instead of the default, which would leave a new feature off for good: use the default.
	 */
	public @Nullable ConfigurationSection getSection(String path) {
		if (this.getConfig().contains(path, true)) return this.getConfig().getConfigurationSection(path);

		Configuration defaults = this.getConfig().getDefaults();
		return defaults == null ? null : defaults.getConfigurationSection(path);
	}

	/** The name of a client mod for messages, from the {@code mods} section of the config; the id itself if it has none. */
	public String getModName(String id) {
		return this.getConfig().getString("mods." + id.toLowerCase(Locale.ROOT), id);
	}

	@Override
	public void onEnable() {
		this.platform = this.loadPlatform();
		this.syncConfig();
		TransUtils.load(this);

		this.viaVersionHook = ViaVersionHook.load(this);
		this.bedrockHook = BedrockHook.load(this);
		this.craftEngineHook = CraftEngineHook.load(this);
		this.itemsAdderHook = ItemsAdderHook.load(this);
		this.nexoHook = NexoHook.load(this);
		this.oraxenHook = OraxenHook.load(this);

		this.updateChecker = new UpdateCheckerModule(this);
		this.modules.add(this.updateChecker);

		VersionAdapter adapter = this.loadAdapter();
		if (adapter != null) {
			this.modCompat = ModCompatModule.create(this, adapter);
			this.modules.add(this.modCompat);
			this.modBlock = new ModBlockModule(this, adapter.createModBlockBridge(this));
			this.modules.add(this.modBlock);
		}

		this.modules.forEach(this::start);

		this.platform.registerCommand(this, new ModUtilsCommand(this));
	}

	@Override
	public void onDisable() {
		this.modules.forEach(FeatureModule::stop);
		this.modules.clear();
		if (this.platform != null) this.platform.close();
		this.modCompat = null;
		this.modBlock = null;
		this.updateChecker = null;
		TransUtils.unload();
	}

	/**
	 * Reads config.yml again and starts every feature afresh. Changes made with {@code /modutils set} are dropped.
	 */
	public void reloadAll() {
		this.modules.forEach(FeatureModule::stop);
		this.syncConfig();
		TransUtils.load(this);
		this.modules.forEach(this::start);
	}

	/** Paper (and Folia, and forks of them) have their own schedulers; everything else runs as plain Bukkit. */
	private Platform loadPlatform() {
		boolean paper;
		try {
			Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
			paper = true;
		} catch (ClassNotFoundException e) {
			paper = false;
		}
		String type = paper ? "me.ronaldi2001.modutils.paper.PaperPlatform" : "me.ronaldi2001.modutils.bukkit.BukkitPlatform";
		try {
			Platform loaded = (Platform) Class.forName(type, true, this.getClass().getClassLoader()).getDeclaredConstructor().newInstance();
			loaded.init(this);
			return loaded;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not load the " + (paper ? "Paper" : "Bukkit") + " platform", e);
		}
	}

	/** Picks the adapter for the running Minecraft version. @return null if there is none, which leaves nothing to start */
	private @Nullable VersionAdapter loadAdapter() {
		AdapterLoader.Loaded loaded;
		try {
			loaded = AdapterLoader.load(this.getClass().getClassLoader(), this.platform);
		} catch (Throwable e) {
			this.getLogger().log(Level.SEVERE, "Could not load the adapter for Minecraft " + this.platform.minecraftVersion(), e);
			return null;
		}
		if (loaded == null) {
			this.getLogger().severe("Minecraft " + this.platform.minecraftVersion()
					+ " is not supported; this plugin needs " + AdapterLoader.oldestSupported() + " or newer");
			return null;
		}
		this.getLogger().info("Using the " + loaded.id() + " adapter for Minecraft " + loaded.minecraftVersion());
		return loaded.adapter();
	}

	/** Starts a feature, and keeps a failure in one from stopping the others. */
	private void start(FeatureModule module) {
		try {
			module.start();
		} catch (Throwable e) {
			this.getLogger().log(Level.SEVERE, "Failed to start " + module.getConfigKey(), e);
		}
	}

	/**
	 * Writes config.yml if it is missing, and otherwise adds the keys it lacks, with their comments, from the one in the
	 * jar. What is already in the file is left as it is. Then (re)loads it.
	 */
	private void syncConfig() {
		this.saveDefaultConfig();
		File file = new File(this.getDataFolder(), "config.yml");
		try (InputStream in = this.getResource("config.yml")) {
			if (in != null) {
				String reference = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				String current = Files.readString(file.toPath(), StandardCharsets.UTF_8);
				String merged = LangFileMerger.merge(current, reference);
				if (!merged.equals(current)) {
					Files.writeString(file.toPath(), merged, StandardCharsets.UTF_8);
					this.getLogger().info("Added missing entries to config.yml");
				}
			}
		} catch (Exception e) {
			this.getLogger().log(Level.WARNING, "Could not add the missing entries to config.yml", e);
		}
		this.reloadConfig();
	}

	public static Key getKey(@KeyPattern.Value String path) {
		return Key.key(PREFIX, path);
	}
}
