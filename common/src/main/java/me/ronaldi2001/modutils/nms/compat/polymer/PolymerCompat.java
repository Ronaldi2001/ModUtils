package me.ronaldi2001.modutils.nms.compat.polymer;

import io.netty.channel.Channel;
import me.ronaldi2001.modutils.nms.ConfigurePhase;
import me.ronaldi2001.modutils.nms.Connections;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.modCompat.api.ModCompat;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.logging.Level;

/**
 * Polymer client support: lets players with the Polymer mod see custom items as what they are in JEI and in their
 * inventory, while everyone else joins as before. See {@link PolymerSupport}.
 * <p>
 * Only exists on versions that have the matching Polymer protocol (26.3).
 */
public final class PolymerCompat extends ModCompat {
	private Runnable unregisterConfigure = () -> {
	};

	public PolymerCompat(ModUtilsPlugin plugin) {
		super(plugin);
	}

	@Override
	public String getConfigKey() {
		return "polymer";
	}

	@Override
	public String getName() {
		return "Polymer";
	}

	@Override
	public void onEnable(ConfigurationSection section) {
		ConfigurationSection detect = section.getConfigurationSection("detect");
		PolymerItems.Detection detection = detect == null ? PolymerItems.Detection.DEFAULT : new PolymerItems.Detection(
				detect.getBoolean("itemModel", true), detect.getBoolean("customModelData", true),
				detect.getBoolean("customData", false), new HashSet<>(detect.getStringList("customDataValues")),
				new HashSet<>(detect.getStringList("customDataIgnore")));

		List<PolymerSupport.IngredientRule> rules = new ArrayList<>();
		for (Map<?, ?> entry : section.getMapList("ingredients")) {
			Object recipes = entry.get("recipes");
			Object item = entry.get("item");
			Object like = entry.get("like");
			if (recipes == null || item == null || like == null) {
				this.plugin.getLogger().warning("[Polymer] An entry under 'ingredients' needs recipes, item and like: " + entry);
				continue;
			}
			rules.add(new PolymerSupport.IngredientRule(globToPattern(recipes.toString()), item.toString(), like.toString()));
		}

		PolymerSupport.INSTANCE.enable(this.plugin, this.plugin.getLogger(), section.getBoolean("debug", false),
				section.getString("tabName", "Server Items"), detection, rules);
		Runnable stopConfigure = ConfigurePhase.register(this.plugin, this::onConfigure);
		Runnable stopReload = this.plugin.getPlatform().onResourcesReloaded(this::onResourcesReloaded);
		this.unregisterConfigure = () -> {
			stopConfigure.run();
			stopReload.run();
		};
	}

	private static Pattern globToPattern(String glob) {
		StringBuilder regex = new StringBuilder();
		for (String part : glob.split("\\*", -1)) {
			if (!regex.isEmpty() || glob.startsWith("*")) regex.append(".*");
			regex.append(Pattern.quote(part));
		}
		return Pattern.compile(regex.toString());
	}

	@Override
	public void onDisable() {
		this.unregisterConfigure.run();
		this.unregisterConfigure = () -> {
		};
		PolymerSupport.INSTANCE.disable();
	}


	/**
	 * A connection in the configuration phase: put the Polymer handler on it and say hello. Clients without the mod
	 * ignore the hello.
	 */
	private void onConfigure(UUID id, String name, Object connection) {
		PolymerSupport support = PolymerSupport.INSTANCE;
		if (!support.isActive()) return;

		try {
			// Bedrock players (Geyser, Floodgate) have no Polymer
			if (this.plugin.getBedrockHook().isBedrock(id)) return;

			Channel channel = Connections.channelOf(connection);
			if (channel == null) {
				support.debug("Could not find the connection of " + id + "; no Polymer for them");
				return;
			}
			support.onConnection(id, channel);
		} catch (Throwable e) {
			this.plugin.getLogger().log(Level.WARNING, "Could not set up Polymer support for a connection", e);
		}
	}

	@Override
	public boolean detect(Player player) {
		PolymerSupport.Session session = PolymerSupport.INSTANCE.session(player.getUniqueId());
		return session != null && session.handshakeSeen;
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		PolymerSupport support = PolymerSupport.INSTANCE;
		Player player = event.getPlayer();
		if (this.plugin.getBedrockHook().isBedrock(player)) return;
		if (support.isActive() && !ConfigurePhase.available()) {
			// This version has no configuration-phase event: say hello now, in the play phase, which Polymer handles too
			this.onConfigure(player.getUniqueId(), player.getName(), ((CraftPlayer) player).getHandle().connection);
		}
		support.onJoin(player);
	}

	private void onResourcesReloaded() {
		PolymerSupport.INSTANCE.invalidateScan();
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		PolymerSupport.INSTANCE.onQuit(event.getPlayer().getUniqueId());
	}
}
