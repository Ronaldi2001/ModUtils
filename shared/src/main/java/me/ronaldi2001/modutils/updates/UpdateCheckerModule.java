package me.ronaldi2001.modutils.updates;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.ronaldi2001.modutils.FeatureModule;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.platform.ScheduledTask;
import me.ronaldi2001.modutils.translations.Argument;
import me.ronaldi2001.modutils.translations.TransUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Looks on Modrinth for a newer version of this plugin and tells whoever has {@link #PERMISSION}: in the console when
 * it finds one, and in chat when such a player joins. Configured in the {@code updateChecker} section of config.yml.
 */
public final class UpdateCheckerModule implements FeatureModule, Listener {
	public static final String CONFIG_KEY = "updateChecker";
	public static final String PERMISSION = "modutils.update";

	private static final String API = "https://api.modrinth.com/v2/project/%s/version";
	private static final String PAGE = "https://modrinth.com/plugin/%s/version/%s";
	private static final String PROJECT = "mod-utils";
	/** Also tell about beta and alpha versions, not only releases. Set to false once the plugin is out of alpha. */
	private static final boolean INCLUDE_PRERELEASES = true;

	private final ModUtilsPlugin plugin;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private boolean active;
	private long intervalHours;
	private long joinDelayTicks;
	private @Nullable ScheduledTask nextCheck;
	/** Bumped on every start and stop, so the answer to a check from before is thrown away. */
	private final AtomicInteger generation = new AtomicInteger();
	private volatile @Nullable Release latest;

	/** @param version the version number on Modrinth, e.g. {@code 1.0.2} */
	private record Release(String version, String url) {
	}

	public UpdateCheckerModule(ModUtilsPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public String getConfigKey() {
		return CONFIG_KEY;
	}

	@Override
	public boolean isActive() {
		return this.active;
	}

	@Override
	public boolean start() {
		this.stop();

		ConfigurationSection config = this.plugin.getSection(CONFIG_KEY);
		if (config == null || !config.getBoolean("enabled", false)) return false;

		this.intervalHours = Math.max(0, config.getLong("intervalHours", 24));
		this.joinDelayTicks = Math.max(0, config.getLong("joinDelaySeconds", 0)) * 20;
		this.active = true;
		this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
		this.check(this.generation.get());
		return true;
	}

	@Override
	public void stop() {
		this.active = false;
		this.generation.incrementAndGet();
		this.latest = null;
		HandlerList.unregisterAll(this);
		if (this.nextCheck != null) {
			this.nextCheck.cancel();
			this.nextCheck = null;
		}
	}

	/** Asks Modrinth off the main thread, and schedules the next time. */
	private void check(int generation) {
		this.plugin.getScheduler().runAsyncDelayed(() -> {
			Release found = null;
			try {
				found = this.fetchLatest();
			} catch (Exception e) {
				this.plugin.getLogger().warning("Could not check Modrinth for updates: " + e.getMessage());
			}
			if (generation != this.generation.get()) return;

			if (found != null && isNewer(found.version(), this.currentVersion())) {
				Release before = this.latest;
				this.latest = found;
				if (before == null || !before.version().equals(found.version())) {
					this.plugin.getLogger().info("A new version is out: " + found.version() + " (this is " + this.currentVersion()
							+ "). Download it at " + found.url());
				}
			} else {
				this.latest = null;
			}

			if (this.intervalHours > 0) {
				this.nextCheck = this.plugin.getScheduler().runAsyncDelayed(() -> {
					if (generation == this.generation.get()) this.check(generation);
				}, this.intervalHours, TimeUnit.HOURS);
			}
		}, 0, TimeUnit.MILLISECONDS);
	}

	/** @return the newest release on Modrinth, or null if there is none */
	private @Nullable Release fetchLatest() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(API.formatted(PROJECT)))
				.timeout(Duration.ofSeconds(15))
				.header("User-Agent", "Ronaldi2001/ModUtils/" + this.currentVersion())
				.GET().build();
		HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) throw new IllegalStateException("Modrinth answered " + response.statusCode());

		JsonArray versions = JsonParser.parseString(response.body()).getAsJsonArray();
		// Modrinth lists the newest first
		for (JsonElement element : versions) {
			JsonObject version = element.getAsJsonObject();
			String type = version.get("version_type").getAsString();
			if (!INCLUDE_PRERELEASES && !type.equals("release")) continue;
			String number = version.get("version_number").getAsString();
			return new Release(number, PAGE.formatted(PROJECT, number));
		}
		return null;
	}

	private String currentVersion() {
		return this.plugin.getPluginMeta().getVersion();
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		if (!player.hasPermission(PERMISSION)) return;
		this.plugin.getScheduler().runOnEntityDelayed(player, () -> this.tell(player), this.joinDelayTicks);
	}

	private void tell(Player player) {
		Release release = this.latest;
		if (!this.active || release == null || !player.isOnline()) return;

		Component message = TransUtils.translatableManual(player, CONFIG_KEY + ".available",
				Argument.string("current", this.currentVersion()),
				Argument.string("latest", release.version()),
				Argument.string("url", release.url()));
		this.plugin.getPlatform().send(player, message.clickEvent(ClickEvent.openUrl(release.url())));
	}

	/** Whether {@code candidate} is a higher version than {@code current}: compares the numbers, ignoring a suffix like -SNAPSHOT. */
	static boolean isNewer(String candidate, String current) {
		int[] a = numbers(candidate);
		int[] b = numbers(current);
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			int x = i < a.length ? a[i] : 0;
			int y = i < b.length ? b[i] : 0;
			if (x != y) return x > y;
		}
		return false;
	}

	private static int[] numbers(String version) {
		String core = version.toLowerCase(Locale.ROOT).replaceFirst("^v", "").split("[-+ ]", 2)[0];
		String[] parts = core.split("\\.");
		int[] result = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			try {
				result[i] = Integer.parseInt(parts[i].replaceAll("\\D.*$", ""));
			} catch (NumberFormatException e) {
				result[i] = 0;
			}
		}
		return result;
	}
}
