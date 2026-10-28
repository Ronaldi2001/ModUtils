package me.ronaldi2001.modutils.nms.compat.appleSkin;

import me.ronaldi2001.modutils.platform.ScheduledTask;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.modCompat.api.ModCompat;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerUnregisterChannelEvent;
import org.bukkit.plugin.messaging.Messenger;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AppleSkin: vanilla only syncs saturation when it hits 0 and never syncs exhaustion, so we push both.
 * <p>
 * Payloads are a single big-endian float. The channels are {@code appleskin:saturation} and
 * {@code appleskin:exhaustion} on every AppleSkin from 1.21.5 to 26.3, Fabric and NeoForge alike; older versions
 * called them {@code ..._sync}, which is still served for them.
 */
public class AppleSkinCompat extends ModCompat {
	private static final String SATURATION_CHANNEL = "appleskin:saturation";
	private static final String EXHAUSTION_CHANNEL = "appleskin:exhaustion";
	private static final String SATURATION_CHANNEL_LEGACY = "appleskin:saturation_sync";
	private static final String EXHAUSTION_CHANNEL_LEGACY = "appleskin:exhaustion_sync";
	private static final String[] CHANNELS = {
			SATURATION_CHANNEL, EXHAUSTION_CHANNEL, SATURATION_CHANNEL_LEGACY, EXHAUSTION_CHANNEL_LEGACY
	};
	private static final float EXHAUSTION_THRESHOLD = 0.01f;
	private static final int SATURATION = 0;
	private static final int EXHAUSTION = 1;
	/**
	 * How long after joining the client's channels are checked for, in case it announced them in a way that fired no
	 * register event (a client that registers during the configuration phase).
	 */
	private static final long JOIN_CHECK_DELAY_TICKS = 40L;

	private int syncIntervalTicks = 10;
	private boolean debug;

	private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
	/** What was last sent to each player, as {saturation, exhaustion}; NaN for nothing sent yet. */
	private final Map<UUID, float[]> lastSent = new ConcurrentHashMap<>();

	public AppleSkinCompat(ModUtilsPlugin plugin) {
		super(plugin);
	}

	@Override
	public String getConfigKey() {
		return "appleSkin";
	}

	@Override
	public String getName() {
		return "AppleSkin";
	}

	@Override
	public void onEnable(ConfigurationSection section) {
		this.syncIntervalTicks = Math.max(1, section.getInt("syncIntervalTicks", 10));
		this.debug = section.getBoolean("debug", false);

		Messenger messenger = this.plugin.getServer().getMessenger();
		for (String channel : CHANNELS) messenger.registerOutgoingPluginChannel(this.plugin, channel);

		// Players already online (e.g. after a reload) that have the mod
		this.plugin.getServer().getOnlinePlayers().stream().filter(this::hasMod).forEach(this::startTracking);
	}

	@Override
	public void onDisable() {
		Messenger messenger = this.plugin.getServer().getMessenger();
		for (String channel : CHANNELS) messenger.unregisterOutgoingPluginChannel(this.plugin, channel);
		this.tasks.values().forEach(ScheduledTask::cancel);
		this.tasks.clear();
		this.lastSent.clear();
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		this.plugin.getScheduler().runOnEntityDelayed(player, () -> {
			boolean hasMod = this.hasMod(player);
			if (this.debug) {
				this.debug(player.getName() + " joined with client brand " + this.plugin.getPlatform().clientBrand(player) + ", "
						+ player.getListeningPluginChannels().size() + " channels registered, AppleSkin channels: "
						+ (hasMod ? "yes" : "none"));
			}
			if (hasMod) this.startTracking(player);
		}, JOIN_CHECK_DELAY_TICKS);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		this.stopTracking(event.getPlayer());
	}

	/**
	 * The client registers its channels after joining, so this is when we learn a player has the mod.
	 */
	@EventHandler
	public void onRegisterChannel(PlayerRegisterChannelEvent event) {
		String channel = event.getChannel();
		if (noAppleSkinChannel(channel)) return;

		this.debug(event.getPlayer().getName() + " registered " + channel + ", syncing every " + this.syncIntervalTicks + " ticks");

		// Fresh values for a client that just registered
		this.startTracking(event.getPlayer());
	}

	@EventHandler
	public void onUnregisterChannel(PlayerUnregisterChannelEvent event) {
		String channel = event.getChannel();
		if (noAppleSkinChannel(channel)) return;

		Player player = event.getPlayer();
		if (!this.hasMod(player)) {
			this.debug(player.getName() + " unregistered " + channel + ", no longer syncing");
			this.stopTracking(player);
		}
	}

	private static boolean noAppleSkinChannel(String channel) {
		for (String known : CHANNELS) {
			if (known.equals(channel)) return false;
		}
		return true;
	}

	@Override
	public boolean detect(Player player) {
		return this.hasMod(player);
	}

	private boolean hasMod(Player player) {
		Set<String> channels = player.getListeningPluginChannels();
		for (String channel : CHANNELS) {
			if (channels.contains(channel)) return true;
		}
		return false;
	}

	/**
	 * The channel this client listens on: the current name, or the older one for an older AppleSkin.
	 *
	 * @return null if it listens on neither
	 */
	private @Nullable String channelFor(Player player, String current, String legacy) {
		Set<String> channels = player.getListeningPluginChannels();
		if (channels.contains(current)) return current;
		return channels.contains(legacy) ? legacy : null;
	}

	private void startTracking(Player player) {
		if (this.plugin.getBedrockHook().isBedrock(player)) return;

		UUID id = player.getUniqueId();
		this.lastSent.put(id, new float[]{Float.NaN, Float.NaN});
		if (this.tasks.containsKey(id)) return;

		// Entity scheduler is Folia-safe and cancels itself when the player is removed.
		ScheduledTask task = this.plugin.getScheduler().runOnEntityTimer(player, () -> this.sync(player), () -> this.stopTracking(player), 1L, this.syncIntervalTicks);
		if (task != null) this.tasks.put(id, task);
	}

	private void stopTracking(Player player) {
		UUID id = player.getUniqueId();
		ScheduledTask task = this.tasks.remove(id);
		if (task != null) task.cancel();
		this.lastSent.remove(id);
	}

	private void sync(Player player) {
		if (!player.isOnline()) return;

		UUID id = player.getUniqueId();

		// One lookup per tick for both values, and the channel list is only read when something changed
		float[] last = this.lastSent.get(id);
		if (last == null) return;

		float saturation = player.getSaturation();
		if (Float.isNaN(last[SATURATION]) || last[SATURATION] != saturation) {
			if (this.send(player, this.channelFor(player, SATURATION_CHANNEL, SATURATION_CHANNEL_LEGACY), saturation)) {
				last[SATURATION] = saturation;
			}
		}

		float exhaustion = player.getExhaustion();
		if (Float.isNaN(last[EXHAUSTION]) || Math.abs(last[EXHAUSTION] - exhaustion) >= EXHAUSTION_THRESHOLD) {
			if (this.send(player, this.channelFor(player, EXHAUSTION_CHANNEL, EXHAUSTION_CHANNEL_LEGACY), exhaustion)) {
				last[EXHAUSTION] = exhaustion;
			}
		}
	}

	/**
	 * @return false if the client doesn't listen on the channel (no mod, or no channel given), so nothing was sent.
	 */
	private boolean send(Player player, @Nullable String channel, float value) {
		if (channel == null) return false;

		player.sendPluginMessage(this.plugin, channel, ByteBuffer.allocate(Float.BYTES).putFloat(value).array());
		this.debug("Sent " + channel + " = " + value + " to " + player.getName());
		return true;
	}

	private void debug(String message) {
		if (this.debug) this.plugin.getLogger().info("[AppleSkin] " + message);
	}
}
