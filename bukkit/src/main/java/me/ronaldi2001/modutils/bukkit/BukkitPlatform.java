package me.ronaldi2001.modutils.bukkit;

import me.ronaldi2001.modutils.platform.CommandActions;
import me.ronaldi2001.modutils.platform.ModScheduler;
import me.ronaldi2001.modutils.platform.Platform;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bukkit, Spigot and CraftBukkit: no region schedulers, no Brigadier and no Adventure of their own. Components are sent
 * with adventure-platform-bukkit, and the client brand is read from the channel it is sent on.
 */
public final class BukkitPlatform implements Platform, Listener, PluginMessageListener {
	private static final String BRAND_CHANNEL = "minecraft:brand";
	/** A kick reason is a plain string here, so the colors go in as legacy codes, hex ones included. */
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().hexColors().useUnusualXRepeatedCharacterHexFormat().build();

	private final Map<UUID, String> brands = new ConcurrentHashMap<>();
	private JavaPlugin plugin;
	private ModScheduler scheduler;
	private BukkitAudiences audiences;

	@Override
	public String id() {
		return "bukkit";
	}

	@Override
	public void init(JavaPlugin plugin) {
		this.plugin = plugin;
		this.scheduler = new BukkitScheduler(plugin);
		this.audiences = BukkitAudiences.create(plugin);
		plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, BRAND_CHANNEL, this);
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
	}

	@Override
	public ModScheduler scheduler() {
		return this.scheduler;
	}

	/** Spigot's own method for the version needs a newer API than the oldest supported, so it is read from the Bukkit version. */
	@Override
	public String minecraftVersion() {
		String bukkit = Bukkit.getBukkitVersion();
		int dash = bukkit.indexOf('-');
		return dash < 0 ? bukkit : bukkit.substring(0, dash);
	}

	@Override
	@SuppressWarnings("deprecation") // Bukkit only has the locale as a string
	public Locale locale(Player player) {
		String tag = player.getLocale();
		return tag.isEmpty() ? Locale.US : Locale.forLanguageTag(tag.replace('_', '-'));
	}

	@Override
	public void send(CommandSender sender, Component message) {
		this.audiences.sender(sender).sendMessage(GlobalTranslator.render(message, senderLocale(sender)));
	}

	@Override
	@SuppressWarnings("deprecation") // the only way to disconnect with a reason on Bukkit
	public void kick(Player player, Component reason) {
		player.kickPlayer(LEGACY.serialize(GlobalTranslator.render(reason, locale(player))));
	}

	@Override
	public @Nullable String clientBrand(Player player) {
		return this.brands.get(player.getUniqueId());
	}

	@Override
	public Runnable onResourcesReloaded(Runnable callback) {
		Listener listener = new ReloadListener(callback);
		this.plugin.getServer().getPluginManager().registerEvents(listener, this.plugin);
		return () -> HandlerList.unregisterAll(listener);
	}

	@Override
	public void registerCommand(JavaPlugin plugin, CommandActions actions) {
		PluginCommand command = plugin.getCommand("modutils");
		if (command == null) {
			plugin.getLogger().warning("The modutils command is missing from plugin.yml");
			return;
		}
		BukkitCommand handler = new BukkitCommand(plugin.getServer(), actions);
		command.setExecutor(handler);
		command.setTabCompleter(handler);
	}

	@Override
	public void close() {
		this.plugin.getServer().getMessenger().unregisterIncomingPluginChannel(this.plugin, BRAND_CHANNEL);
		HandlerList.unregisterAll((Listener) this);
		this.brands.clear();
		this.audiences.close();
	}

	/** The client sends its brand as a string with a VarInt length in front, right after it joins. */
	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		int index = 0;
		int length = 0;
		int shift = 0;
		while (index < message.length) {
			byte next = message[index++];
			length |= (next & 0x7F) << shift;
			shift += 7;
			if ((next & 0x80) == 0 || shift > 28) break;
		}
		if (length < 0 || index + length > message.length) return;
		this.brands.put(player.getUniqueId(), new String(message, index, length, StandardCharsets.UTF_8));
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		this.brands.remove(event.getPlayer().getUniqueId());
	}

	private Locale senderLocale(CommandSender sender) {
		return sender instanceof Player player ? locale(player) : Locale.US;
	}

	/** Bukkit has no event for the data reload on its own; {@code /reload} comes with a {@link ServerLoadEvent}. */
	private record ReloadListener(Runnable callback) implements Listener {
		@EventHandler
		public void onLoad(ServerLoadEvent event) {
			if (event.getType() == ServerLoadEvent.LoadType.RELOAD) this.callback.run();
		}
	}
}
