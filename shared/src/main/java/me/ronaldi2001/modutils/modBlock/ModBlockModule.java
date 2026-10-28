package me.ronaldi2001.modutils.modBlock;

import me.ronaldi2001.modutils.FeatureModule;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.translations.Argument;
import me.ronaldi2001.modutils.translations.TransUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Kicks players whose client has mods you don't want.
 * <p>
 * Two detection methods, both configured in the {@code modBlock} section of config.yml:
 * <ul>
 *     <li><b>Channels:</b> mods that use networking announce their channels ({@code modid:something}) through
 *     {@code minecraft:register}. Any channel whose namespace is in {@code blockedMods} is a hit.</li>
 *     <li><b>Translation keys (virtual sign trick):</b> mods without networking still ship language files. We show
 *     the client a fake sign whose lines are translatable components, open the sign editor and close it again. The
 *     client replies with the text it rendered; a line that is not our fallback means the key exists, so the mod is
 *     installed.</li>
 * </ul>
 * After {@code scanSeconds} the player is kicked with every mod that was found. What the clients send is read through a
 * {@link ModBlockBridge}, which the version adapter provides.
 */
public final class ModBlockModule implements FeatureModule, Listener, ModBlockBridge.Listener {
	public static final String CONFIG_KEY = "modBlock";
	public static final String BYPASS_PERMISSION = "modutils.modblock.bypass";

	private static final int LINES_PER_SIGN = 4;
	/** Returned by the client when a translation key does not exist. Cannot clash with a real translation. */
	private static final String FALLBACK = "§rmodutils-modblock-missing";
	private static final int PROBE_START_DELAY_TICKS = 20;
	private static final int PROBE_CLOSE_DELAY_TICKS = 2;
	private static final int PROBE_TIMEOUT_TICKS = 40;
	private static final int FINISH_RECHECK_TICKS = 10;
	/** Namespaces that every client uses and say nothing about installed mods. */
	private static final Set<String> IGNORED_NAMESPACES = Set.of("minecraft", "bungeecord", "bukkit", "paper", "velocity");

	private final ModUtilsPlugin plugin;
	private final ModBlockBridge bridge;
	private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
	/** Channels per player. Kept apart from sessions because they arrive before the join event. */
	private final Map<UUID, Set<String>> channels = new ConcurrentHashMap<>();

	/** Lowercase mod ids, or exact channels. */
	private final Set<String> blockedMods = new LinkedHashSet<>();
	private final List<Probe> probes = new ArrayList<>();

	private int scanTicks;
	private int maxScanTicks;
	private boolean debug;
	private boolean active;

	public ModBlockModule(ModUtilsPlugin plugin, ModBlockBridge bridge) {
		this.plugin = plugin;
		this.bridge = bridge;
	}

	@Override
	public String getConfigKey() {
		return CONFIG_KEY;
	}

	@Override
	public boolean isActive() {
		return this.active;
	}

	/** Switches on if the {@code modBlock} section of the config says so. */
	@Override
	public boolean start() {
		this.stop();

		ConfigurationSection config = this.plugin.getSection(CONFIG_KEY);
		if (config == null || !config.getBoolean("enabled", false)) return false;

		try {
			this.load(config);
			this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
			this.bridge.start(this);
			this.active = true;
			this.plugin.getLogger().info("Mod block enabled");
		} catch (Throwable e) {
			this.plugin.getLogger().log(Level.SEVERE, "Failed to enable mod block", e);
			this.stop();
		}
		return this.active;
	}

	private void load(ConfigurationSection config) {
		this.scanTicks = Math.max(1, config.getInt("scanSeconds", 5)) * 20;
		this.maxScanTicks = Math.max(this.scanTicks, config.getInt("maxScanSeconds", 15) * 20);
		this.debug = config.getBoolean("debug", false);

		this.blockedMods.clear();
		for (String id : config.getStringList("blockedMods")) {
			if (!id.isBlank()) this.blockedMods.add(id.trim().toLowerCase(Locale.ROOT));
		}

		this.probes.clear();
		ConfigurationSection translations = config.getConfigurationSection("translationChecks");
		if (translations != null) {
			for (String modId : translations.getKeys(false)) {
				for (String key : translations.getStringList(modId)) {
					if (!key.isBlank()) this.probes.add(new Probe(modId.toLowerCase(Locale.ROOT), key.trim()));
				}
			}
		}
	}

	@Override
	public void stop() {
		this.active = false;
		HandlerList.unregisterAll(this);
		this.bridge.stop();
		this.sessions.clear();
		this.channels.clear();
	}

	// ---------------------------------------------------------------- lifecycle

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		if (player.hasPermission(BYPASS_PERMISSION)) return;
		if (this.plugin.getBedrockHook().isBedrock(player)) return;

		// Servers that don't announce connections in the configuration phase are first watched here
		this.bridge.attach(player);

		Session session = new Session();
		this.sessions.put(player.getUniqueId(), session);

		if (this.probes.isEmpty()) {
			session.probesDone = true;
		} else {
			this.plugin.getScheduler().runOnEntityDelayed(player, () -> this.sendNextRound(player, session), PROBE_START_DELAY_TICKS);
		}
		this.plugin.getScheduler().runOnEntityDelayed(player, () -> this.tryFinish(player, session, this.scanTicks), this.scanTicks);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		UUID uuid = event.getPlayer().getUniqueId();
		this.sessions.remove(uuid);
		this.channels.remove(uuid);
	}

	/** Kicks once the scan time has passed and every sign probe has been answered (or the hard cap is hit). */
	private void tryFinish(Player player, Session session, int elapsedTicks) {
		if (this.sessions.get(player.getUniqueId()) != session || !player.isOnline()) return;

		if (!session.probesDone && elapsedTicks < this.maxScanTicks) {
			this.plugin.getScheduler().runOnEntityDelayed(
					player, () -> this.tryFinish(player, session, elapsedTicks + FINISH_RECHECK_TICKS), FINISH_RECHECK_TICKS
			);
			return;
		}

		this.sessions.remove(player.getUniqueId());

		Set<String> detected = new LinkedHashSet<>(this.detectChannelMods(player));
		detected.addAll(session.detectedTranslations);
		if (detected.isEmpty()) return;

		this.plugin.getLogger().info(player.getName() + " was kicked for blocked mods: "
				+ detected.stream().map(this.plugin::getModName).collect(Collectors.joining(", ")));

		List<Component> entries = detected.stream()
				.map(id -> (Component) TransUtils.translatable(CONFIG_KEY + ".kick.entry", Argument.string("mod", this.plugin.getModName(id))))
				.toList();
		this.plugin.getPlatform().kick(player, TransUtils.translatableManual(
				player,
				CONFIG_KEY + ".kick.header",
				Argument.component("mods", Component.join(JoinConfiguration.newlines(), entries))
		));
	}

	/** Whether debug logging is on. */
	public boolean isDebug() {
		return this.active && this.debug;
	}

	/** The ids of the mods that get players kicked: the blocked channels and the ones with translation checks. */
	public Set<String> getBlockedMods() {
		Set<String> ids = new LinkedHashSet<>();
		for (String entry : this.blockedMods) ids.add(namespaceOf(entry));
		for (Probe probe : this.probes) ids.add(probe.mod());
		return ids;
	}

	/** The ids of the blocked mods whose channels the player has. */
	private Set<String> detectChannelMods(Player player) {
		if (this.blockedMods.isEmpty()) return Set.of();

		Set<String> all = new HashSet<>(this.channels.getOrDefault(player.getUniqueId(), Set.of()));
		all.addAll(player.getListeningPluginChannels());

		Set<String> found = new LinkedHashSet<>();
		for (String channel : all) {
			String lower = channel.toLowerCase(Locale.ROOT);
			String id = namespaceOf(lower);
			if (this.blockedMods.contains(lower) || this.blockedMods.contains(id)) found.add(id);
		}
		return found;
	}

	private static String namespaceOf(String channel) {
		int colon = channel.indexOf(':');
		return colon < 0 ? channel : channel.substring(0, colon);
	}

	// ---------------------------------------------------------------- virtual sign probe

	private void sendNextRound(Player player, Session session) {
		if (this.sessions.get(player.getUniqueId()) != session || !player.isOnline()) return;

		int from = session.nextProbe;
		if (from >= this.probes.size()) {
			session.probesDone = true;
			return;
		}
		int to = Math.min(from + LINES_PER_SIGN, this.probes.size());
		session.nextProbe = to;

		// Bottom of the world under the player: always inside a chunk the client has loaded
		Location loc = player.getLocation();
		Pos pos = new Pos(loc.getBlockX(), player.getWorld().getMinHeight(), loc.getBlockZ());
		Round round = new Round(pos, List.copyOf(this.probes.subList(from, to)));
		session.round = round;

		this.bridge.openProbe(player, pos.x(), pos.y(), pos.z(), round.probes().stream().map(Probe::key).toList(), FALLBACK);

		// Closing the screen is what makes the client send its sign text back
		this.plugin.getScheduler().runOnEntityDelayed(
				player, () -> {
					if (session.round == round) this.bridge.closeScreen(player);
				}, PROBE_CLOSE_DELAY_TICKS
		);

		// No reply (modified client, lag): move on so the scan can't hang
		this.plugin.getScheduler().runOnEntityDelayed(player, () -> this.finishRound(player, session, round), PROBE_TIMEOUT_TICKS);
	}

	/** Restores the real block and starts the next round. Must run on the player's scheduler. */
	private void finishRound(Player player, Session session, Round round) {
		if (session.round != round) return;
		session.round = null;
		if (!player.isOnline()) return;

		Location real = new Location(player.getWorld(), round.pos().x(), round.pos().y(), round.pos().z());
		player.sendBlockChange(real, real.getBlock().getBlockData());
		this.sendNextRound(player, session);
	}

	private void handleSignReply(Player player, Session session, Round round, String[] lines) {
		for (int i = 0; i < round.probes().size() && i < lines.length; i++) {
			if (FALLBACK.equals(lines[i])) continue;

			Probe probe = round.probes().get(i);
			if (session.detectedTranslations.add(probe.mod())) {
				this.debug(player.getName() + " resolved '" + probe.key() + "' -> " + probe.mod());
			}
		}
		this.plugin.getScheduler().runOnEntity(player, () -> this.finishRound(player, session, round));
	}

	private void debug(String message) {
		if (this.debug) this.plugin.getLogger().info("[ModBlock] " + message);
	}

	// ---------------------------------------------------------------- what the clients send

	@Override
	public void onChannel(UUID id, String channel, byte[] data) {
		Set<String> set = this.channels.computeIfAbsent(id, key -> ConcurrentHashMap.newKeySet());
		switch (channel) {
			case "minecraft:register" -> {
				for (String name : new String(data, StandardCharsets.UTF_8).split("\0")) {
					if (!name.isBlank()) set.add(name);
				}
			}
			case "minecraft:unregister" -> {
				for (String name : new String(data, StandardCharsets.UTF_8).split("\0")) set.remove(name);
			}
			default -> {
				// A client sending on a channel proves the mod is there, registered or not
				if (!IGNORED_NAMESPACES.contains(namespaceOf(channel))) set.add(channel);
			}
		}
	}

	@Override
	public boolean onSignUpdate(UUID id, int x, int y, int z, String[] lines) {
		Session session = this.sessions.get(id);
		Round round = session == null ? null : session.round;
		if (round == null || !round.pos().equals(new Pos(x, y, z))) return false;

		Player player = Bukkit.getPlayer(id);
		if (player != null) this.handleSignReply(player, session, round, lines);
		return true;
	}

	// ---------------------------------------------------------------- data

	/** A translation key that tells whether the mod with this id is installed. */
	private record Probe(String mod, String key) {
	}

	private record Pos(int x, int y, int z) {
	}

	private record Round(Pos pos, List<Probe> probes) {
	}

	private static final class Session {
		final Set<String> detectedTranslations = ConcurrentHashMap.newKeySet();
		volatile @Nullable Round round;
		volatile boolean probesDone;
		int nextProbe;
	}
}
