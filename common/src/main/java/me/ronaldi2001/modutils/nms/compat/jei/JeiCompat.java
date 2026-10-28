package me.ronaldi2001.modutils.nms.compat.jei;

import me.ronaldi2001.modutils.nms.ServerApi;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import java.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.modCompat.api.ModCompat;
import me.ronaldi2001.modutils.nms.ConfigurePhase;
import me.ronaldi2001.modutils.nms.Connections;
import me.ronaldi2001.modutils.nms.ItemTranslator;
import me.ronaldi2001.modutils.nms.compat.polymer.PolymerHook;
import me.ronaldi2001.modutils.nms.compat.polymer.PolymerSupport;
import me.ronaldi2001.modutils.pluginHooks.via.ViaVersionHook;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * JEI: serves the recipe transfer packets (the "+" button and shift-click that fills a crafting grid from your
 * inventory) and sends the server's recipes (see {@link RecipeSync}). JEI's client mod sends slot numbers only, so item ids and custom models are never involved: items are
 * matched by the real stacks in the player's inventory, components included.
 * <p>
 * Fabric and NeoForge clients both work: the transfer channels are the same, and the recipes go out in each loader's
 * own payload, picked by the client brand.
 * <p>
 * Channels (JEI protocol, same on 1.21.11 and 26.3): {@code jei:recipe_transfer} and
 * {@code jei:recipe_transfer_counted} (older, no reply) plus the {@code ..._with_result} variants, which are answered
 * on {@code jei:recipe_transfer_result} so the client can tell whether it worked.
 */
public class JeiCompat extends ModCompat implements PluginMessageListener {
	private static final String TRANSFER = "jei:recipe_transfer";
	private static final String TRANSFER_COUNTED = "jei:recipe_transfer_counted";
	private static final String TRANSFER_WITH_RESULT = "jei:recipe_transfer_with_result";
	private static final String TRANSFER_COUNTED_WITH_RESULT = "jei:recipe_transfer_counted_with_result";
	private static final String TRANSFER_RESULT = "jei:recipe_transfer_result";
	// Cheat-mode channels. We never act on give/delete/hotbar, but JEI decides "is JEI on the server?" by whether the
	// server listens on jei:delete_player_item, and the transfer buttons stay disabled without it.
	private static final String DELETE_ITEM = "jei:delete_player_item";
	private static final String GIVE_ITEM = "jei:give_item_stack";
	private static final String SET_HOTBAR = "jei:set_hotbar_item_stack";
	private static final String REQUEST_CHEAT = "jei:request_cheat_permission";
	private static final String CHEAT_PERMISSION = "jei:cheat_permission";
	private static final String[] INCOMING = {
			TRANSFER, TRANSFER_COUNTED, TRANSFER_WITH_RESULT, TRANSFER_COUNTED_WITH_RESULT,
			DELETE_ITEM, GIVE_ITEM, SET_HOTBAR, REQUEST_CHEAT
	};

	/**
	 * The entity event that tells a client its player has no operator permissions (24 + the permission level).
	 */
	private static final byte PERMISSION_LEVEL_NONE = 24;
	/**
	 * The brand a Fabric client reports.
	 */
	private static final String BRAND_FABRIC = "fabric";
	/**
	 * The brand a NeoForge client reports.
	 */
	private static final String BRAND_NEOFORGE = "neoforge";
	/**
	 * Ticks to wait after a reload before resending recipes to NeoForge clients. Paper sends its own recipe update
	 * to everyone when a reload finishes, and the recipes must come after it.
	 */
	private static final long NEOFORGE_RESEND_DELAY_TICKS = 10L;
	/**
	 * How long recipes wait for a Polymer handshake before they go out without it.
	 */
	private static final long POLYMER_WAIT_TICKS = 100L;
	/**
	 * How long recipes wait after the custom items were sent to a Polymer client.
	 */
	private static final long POLYMER_RECIPE_DELAY_TICKS = 10L;

	// Protocol numbers, written out instead of taken from ViaVersion so this class doesn't need it to load.
	/**
	 * The server's own version, which is also what a client without ViaVersion is on.
	 */
	private static final int SERVER_PROTOCOL = SharedConstants.getProtocolVersion();

	private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();
	private RecipeTransfer transfer;
	private boolean transferEnabled = true;

	/** Set by {@link ServerLoadEvent}; recipes are only read ahead of time once startup (and every plugin's recipes) is done. */
	private volatile boolean serverLoaded;

	private volatile boolean warmUpPending;
	private long minRequestIntervalNanos;
	private boolean debug;
	private RecipeSync recipeSync;
	/**
	 * The hold on each joining player's recipe update (see {@link RecipeUpdateHold}), with their channel.
	 */
	private final Map<UUID, Held> holds = new ConcurrentHashMap<>();
	/**
	 * Players that already got the recipes this session.
	 */
	private final Set<UUID> synced = ConcurrentHashMap.newKeySet();
	/**
	 * Polymer client support (see {@link PolymerHook}); does nothing on versions without it.
	 */
	private final PolymerHook polymer = PolymerSupport.INSTANCE;
	/** Given to Polymer to call when a client finishes its handshake; kept so it can be taken back on disable. */
	private final java.util.function.Consumer<Player> readyCallback = this::onPolymerReady;
	private Runnable unregisterConfigure = () -> {
	};
	/**
	 * Players whose recipes wait for their Polymer handshake to finish.
	 */
	private final Set<UUID> waitingForPolymer = ConcurrentHashMap.newKeySet();

	public JeiCompat(ModUtilsPlugin plugin) {
		super(plugin);
	}

	@Override
	public String getConfigKey() {
		return "jei";
	}

	@Override
	public String getName() {
		return "JEI";
	}

	@Override
	public void onEnable(ConfigurationSection section) {
		this.debug = section.getBoolean("debug", false);
		this.minRequestIntervalNanos = Math.max(0L, section.getLong("minRequestIntervalMs", 100L)) * 1_000_000L;
		this.transfer = new RecipeTransfer(this.plugin.getLogger());

		ConfigurationSection features = section.getConfigurationSection("features");
		this.transferEnabled = features == null || features.getBoolean("recipeTransfer", true);
		boolean syncEnabled = features == null || features.getBoolean("recipeSync", true);
		RecipeDisplayConfig display = RecipeDisplayConfig.parse(section, this.plugin.getLogger());
		this.recipeSync = syncEnabled ? new RecipeSync(this.plugin, this.debug, display, this.polymer) : null;
		if (this.debug && syncEnabled) {
			try {
				RecipeSelfTest.run(this.plugin.getLogger(), display, SERVER_PROTOCOL);
			} catch (Throwable e) {
				this.plugin.getLogger().log(Level.WARNING, "[JEI] The recipe self-test failed to run", e);
			}
		}
		this.polymer.onReady(this.readyCallback);
		Runnable stopConfigure = ConfigurePhase.register(this.plugin, this::onConfigure);
		Runnable stopReload = this.plugin.getPlatform().onResourcesReloaded(this::onResourcesReloaded);
		this.unregisterConfigure = () -> {
			stopConfigure.run();
			stopReload.run();
		};
		this.warmUp();

		Messenger messenger = this.plugin.getServer().getMessenger();
		for (String channel : INCOMING) {
			messenger.registerIncomingPluginChannel(this.plugin, channel, this);
		}
		messenger.registerOutgoingPluginChannel(this.plugin, TRANSFER_RESULT);
		messenger.registerOutgoingPluginChannel(this.plugin, CHEAT_PERMISSION);
	}

	@Override
	public void onDisable() {
		this.polymer.removeReady(this.readyCallback);
		this.unregisterConfigure.run();
		this.unregisterConfigure = () -> {
		};

		// Anyone still waiting for their held-back recipe update gets it now
		for (Map.Entry<UUID, Held> entry : this.holds.entrySet()) {
			Held held = entry.getValue();
			held.hold().release(held.channel());
			Player player = this.plugin.getServer().getPlayer(entry.getKey());
			if (player != null && held.hold().wasHeld() && this.recipeSync != null) {
				this.recipeSync.sendUpdateOnly(((CraftPlayer) player).getHandle());
			}
		}
		this.holds.clear();
		this.synced.clear();

		Messenger messenger = this.plugin.getServer().getMessenger();
		for (String channel : INCOMING) {
			messenger.unregisterIncomingPluginChannel(this.plugin, channel, this);
		}
		messenger.unregisterOutgoingPluginChannel(this.plugin, TRANSFER_RESULT);
		messenger.unregisterOutgoingPluginChannel(this.plugin, CHEAT_PERMISSION);
		this.lastRequest.clear();
	}

	@EventHandler
	public void onServerLoad(ServerLoadEvent event) {
		this.serverLoaded = true;
		if (this.warmUpPending) {
			this.warmUpPending = false;
			this.warmUp();
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		UUID id = event.getPlayer().getUniqueId();
		this.lastRequest.remove(id);
		Held held = this.holds.remove(id);
		if (held != null) held.hold().release(held.channel());
		this.synced.remove(id);
		this.waitingForPolymer.remove(id);
	}

	/**
	 * Has the recipe payloads for the usual client ready before anyone joins (see {@link RecipeSync#warmUp}), off the
	 * main thread.
	 */
	private void warmUp() {
		RecipeSync sync = this.recipeSync;
		if (sync == null) return;

		// Other plugins add their recipes while the server is still starting; reading them at the same time throws
		if (!this.serverLoaded) {
			this.warmUpPending = true;
			return;
		}

		this.plugin.getScheduler().runAsyncDelayed(() -> {
			try {
				sync.warmUp(Locale.US);
			} catch (Throwable e) {
				this.plugin.getLogger().log(Level.WARNING, "[JEI] Could not build the recipes ahead of time", e);
			}
		}, 2, java.util.concurrent.TimeUnit.SECONDS);
	}

	/**
	 * A joining player's hold and the channel it sits on.
	 */
	private record Held(RecipeUpdateHold hold, Channel channel) {
	}

	/**
	 * Puts the hold on the connection while the player is still configuring, which is before Paper sends the join-time
	 * recipe update (see {@link RecipeUpdateHold}). It is released and the update sent in {@link #onJoin}. Versions
	 * that do not tell plugins about the configuration phase have no hold; the recipes go out on join instead.
	 */
	private void onConfigure(UUID id, String name, Object connection) {
		if (this.recipeSync == null || this.plugin.getBedrockHook().isBedrock(id)) return;

		try {
			Channel channel = Connections.channelOf(connection);
			if (channel == null) {
				this.debug("Could not find the connection to hold the recipe update on; JEI may warn on join");
				return;
			}

			RecipeUpdateHold hold = new RecipeUpdateHold();
			channel.pipeline().addAfter("encoder", RecipeUpdateHold.NAME, hold);
			this.holds.put(id, new Held(hold, channel));
			this.debug("Hook placed on the connection of " + name + " to hold the recipe update");
		} catch (Exception e) {
			this.plugin.getLogger().log(Level.WARNING, "Could not hold the recipe update for JEI; it may warn on join", e);
		}
	}

	/**
	 * Sends the held-back recipe update, with the recipes around it for a client that can use them: in front of it on
	 * Fabric, behind it on NeoForge (see {@link RecipeSync.Platform}).
	 */
	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		UUID id = player.getUniqueId();
		if (this.plugin.getBedrockHook().isBedrock(id)) return;
		Held held = this.holds.remove(id);
		if (this.recipeSync == null) return;
		if (held != null) {
			// From here the connection passes everything, and the recipe update is ours to send
			held.hold().release(held.channel());
			this.debug(player.getName() + " joined; recipe update was " + (held.hold().wasHeld() ? "held back" : "NOT seen")
					+ ", client brand: " + this.plugin.getPlatform().clientBrand(player) + ", protocol " + this.clientProtocol(player));
			if (!held.hold().wasHeld()) return;
		} else if (ConfigurePhase.available()) {
			return;
		} else {
			// No hold on this version: Paper's own recipe update already went out, ours follows it
			this.debug(player.getName() + " joined; client brand: " + this.plugin.getPlatform().clientBrand(player) + ", protocol "
					+ this.clientProtocol(player));
		}

		if (this.polymer.pending(id)) {
			// The client is in the middle of its Polymer handshake: send once it knows which custom items exist
			this.waitingForPolymer.add(id);
			this.plugin.getScheduler().runOnEntityDelayed(player, () -> {
				if (this.waitingForPolymer.remove(id)) this.deliverRecipes(player);
			}, POLYMER_WAIT_TICKS);
			return;
		}
		if (this.polymer.knowsCustomItems(id)) {
			// Synced already. The custom items and their tab go first; the recipes start JEI, and with it the Polymer
			// plugin that collects the items from the tab, which the client only fills in a moment after it arrives.
			this.deliverAfterTab(player);
			return;
		}
		this.deliverRecipes(player);
	}

	/**
	 * Sends the recipes the way the client's loader wants them (see {@link #onJoin}).
	 */
	private void deliverRecipes(Player player) {
		this.withoutOperatorPermissions(player, () -> this.deliverRecipesNow(player));
	}

	/**
	 * JEI lists the operator items (command blocks, light, structure blocks, ...) when it starts and the client thinks
	 * the player is an operator. On a plain server JEI starts on the recipe update, which Paper sends before the
	 * permission level, so they stay hidden. Our update goes out later (see {@link RecipeUpdateHold}), after the client
	 * has been told. So an operator's client is told "no permissions" while the recipes and the update go out, and the
	 * real level is sent again right after, which is all a plain server does to the client.
	 */
	private void withoutOperatorPermissions(Player player, Runnable send) {
		ServerPlayer handle = ((CraftPlayer) player).getHandle();
		boolean operator = player.isOp();
		if (operator) handle.connection.send(new ClientboundEntityEventPacket(handle, PERMISSION_LEVEL_NONE));
		try {
			send.run();
		} finally {
			if (operator) MinecraftServer.getServer().getPlayerList().sendPlayerPermissionLevel(handle);
		}
	}

	private void deliverRecipesNow(Player player) {
		UUID id = player.getUniqueId();
		String brand = this.plugin.getPlatform().clientBrand(player);
		if (BRAND_FABRIC.equals(brand) && this.sendRecipes(player, RecipeSync.Platform.FABRIC)) {
			this.synced.add(id);
			return;
		}

		this.recipeSync.sendUpdateOnly(((CraftPlayer) player).getHandle());
		if (BRAND_NEOFORGE.equals(brand) && this.sendRecipes(player, RecipeSync.Platform.NEOFORGE)) {
			this.synced.add(id);
		}
	}

	/**
	 * A player's Polymer handshake finished. If their recipes were waiting for it they go out now; if they already
	 * got them, they are sent again with the custom items in them.
	 */
	private void onPolymerReady(Player player) {
		if (this.recipeSync == null || !player.isOnline()) return;

		UUID id = player.getUniqueId();
		if (this.waitingForPolymer.contains(id) || this.synced.contains(id)) this.deliverAfterTab(player);
	}

	/**
	 * The custom items go first. JEI reads them once, when it loads, so the recipes (which make it load) wait a moment
	 * for the client to have taken them in. The player stays in {@link #waitingForPolymer} until then, so nothing else
	 * sends the recipes in the meantime (the client registering its channel does, for instance), and they go out once.
	 */
	private void deliverAfterTab(Player player) {
		UUID id = player.getUniqueId();
		this.waitingForPolymer.add(id);
		this.plugin.getScheduler().runOnEntity(player, () -> {
			this.polymer.ensureSynced(player);
			this.plugin.getScheduler().runOnEntityDelayed(player, () -> {
				if (this.waitingForPolymer.remove(id)) this.deliverRecipes(player);
			}, POLYMER_RECIPE_DELAY_TICKS);
		});
	}

	/**
	 * The client registers {@code fabric:recipe_sync} once it can receive it. This covers a Fabric client whose brand
	 * wasn't known when it joined, so the recipes still arrive, if later.
	 */
	@EventHandler
	public void onRegisterChannel(PlayerRegisterChannelEvent event) {
		if (this.recipeSync == null || !RecipeSync.CHANNEL.equals(event.getChannel())) return;

		Player player = event.getPlayer();
		UUID id = player.getUniqueId();
		if (this.plugin.getBedrockHook().isBedrock(id)) return;
		// Recipes that are already sent, or are waiting for a Polymer handshake, go out on their own
		if (this.synced.contains(id) || this.waitingForPolymer.contains(id)) return;

		this.plugin.getScheduler().runOnEntity(
				player, () -> {
					if (this.synced.contains(id) || this.waitingForPolymer.contains(id)) return;
					this.withoutOperatorPermissions(player, () -> {
						if (this.sendRecipes(player, RecipeSync.Platform.FABRIC)) this.synced.add(id);
					});
				}
		);
	}

	/**
	 * @return whether the recipes were sent. False for a client version the recipe layout hasn't been written for,
	 * where a client that can't decode it would disconnect.
	 */
	private boolean sendRecipes(Player player, RecipeSync.Platform platform) {
		int protocol = this.clientProtocol(player);
		if (RecipeSync.notSupported(platform, protocol)) {
			this.debug("Not sending recipes to " + player.getName() + ": " + platform + " clients on protocol " + protocol
					+ " have no recipe sync");
			return false;
		}
		RecipeTranscoder.Layout layout = this.layoutFor(protocol);
		if (layout == null) {
			this.debug("Not sending recipes to " + player.getName() + ": client protocol " + protocol + " is not supported");
			return false;
		}
		ItemTranslator translator = ItemTranslator.forPlayer(this.plugin, player);
		// A client on another version needs the items translated, which takes ViaVersion
		if (protocol != SERVER_PROTOCOL && translator == null) return false;

		Locale locale = ServerApi.locale(player);
		try {
			this.recipeSync.send(
					platform, player, ((CraftPlayer) player).getHandle(), layout, translator, locale,
					protocol + ":" + locale
			);
			return true;
		} catch (Exception e) {
			this.plugin.getLogger().log(Level.WARNING, "Failed to send recipes to " + player.getName(), e);
			return false;
		}
	}

	private int clientProtocol(Player player) {
		ViaVersionHook via = this.plugin.getViaVersionHook();
		return via == null ? SERVER_PROTOCOL : via.getPlayerVersion(player);
	}

	/**
	 * The recipe layout for a client protocol version, or null if it isn't one we know the layout of, where a client
	 * that can't decode it would disconnect.
	 */
	private RecipeTranscoder.@Nullable Layout layoutFor(int protocol) {
		return RecipeTranscoder.Layout.forProtocol(protocol);
	}

	/**
	 * /reload and datapack reloads change the recipes. Paper then sends everyone a recipe update, which makes a NeoForge
	 * client drop the recipes it had, so those are sent again behind it.
	 */
	private void onResourcesReloaded() {
		if (this.recipeSync == null) return;
		this.recipeSync.invalidate();
		this.warmUp();

		for (Player player : this.plugin.getServer().getOnlinePlayers()) {
			if (!this.synced.contains(player.getUniqueId()) || !BRAND_NEOFORGE.equals(this.plugin.getPlatform().clientBrand(player))) continue;
			this.plugin.getScheduler().runOnEntityDelayed(
					player, () -> this.sendRecipes(player, RecipeSync.Platform.NEOFORGE), NEOFORGE_RESEND_DELAY_TICKS
			);
		}
	}

	@Override
	public void onPluginMessageReceived(@NonNull String channel, @NonNull Player player, byte @NonNull [] message) {
		if (this.plugin.getBedrockHook().isBedrock(player)) return;
		// Only players whose client registered a JEI channel have the mod
		if (player.getListeningPluginChannels().stream().noneMatch(c -> c.startsWith("jei:"))) return;
		if (this.isRateLimited(player)) return;

		if (channel.equals(REQUEST_CHEAT)) {
			// Cheat mode (give/delete items) is deliberately not supported: tell the client it isn't allowed
			this.sendCheatPermission(player);
			return;
		}
		if (channel.equals(DELETE_ITEM) || channel.equals(GIVE_ITEM) || channel.equals(SET_HOTBAR)) return;
		if (!this.transferEnabled) return;

		boolean counted = channel.equals(TRANSFER_COUNTED) || channel.equals(TRANSFER_COUNTED_WITH_RESULT);
		boolean withResult = channel.equals(TRANSFER_WITH_RESULT) || channel.equals(TRANSFER_COUNTED_WITH_RESULT);

		ByteBuf raw = Unpooled.wrappedBuffer(message);
		try {
			FriendlyByteBuf in = new FriendlyByteBuf(raw);
			List<RecipeTransfer.Operation> operations = this.readOperations(in, counted);
			List<Integer> craftingSlots = this.readInts(in);
			List<Integer> inventorySlots = this.readInts(in);
			boolean maxTransfer = in.readBoolean();
			boolean requireCompleteSets = in.readBoolean();
			int transferId = withResult ? in.readVarInt() : -1;

			this.debug("Transfer from " + player.getName() + " via " + channel + ": " + operations.size() + " ops");

			ServerPlayer handle = ((CraftPlayer) player).getHandle();
			this.plugin.getScheduler().runOnEntity(
					player, () -> {
						boolean success;
						try {
							success = this.transfer.transfer(handle, operations, craftingSlots, inventorySlots, maxTransfer, requireCompleteSets);
						} catch (Exception e) {
							this.plugin.getLogger().log(Level.WARNING, "JEI recipe transfer failed for " + player.getName(), e);
							success = false;
						}
						this.debug("Transfer for " + player.getName() + (success ? " succeeded" : " was rejected"));
						if (withResult) this.sendResult(player, transferId, success);
					}
			);
		} catch (Exception e) {
			this.plugin.getLogger().log(Level.WARNING, "Bad JEI packet " + channel + " from " + player.getName(), e);
		} finally {
			raw.release();
		}
	}

	private List<RecipeTransfer.Operation> readOperations(FriendlyByteBuf in, boolean counted) {
		int size = this.readSize(in);
		List<RecipeTransfer.Operation> operations = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			int inventorySlot = in.readVarInt();
			int craftingSlot = in.readVarInt();
			int count = counted ? in.readVarInt() : 1;
			if (count < 1 || count > 99) throw new IllegalArgumentException("Bad transfer count " + count);
			operations.add(new RecipeTransfer.Operation(inventorySlot, craftingSlot, count));
		}
		return operations;
	}

	private List<Integer> readInts(FriendlyByteBuf in) {
		int size = this.readSize(in);
		List<Integer> values = new ArrayList<>(size);
		for (int i = 0; i < size; i++) values.add(in.readVarInt());
		return values;
	}

	private int readSize(FriendlyByteBuf in) {
		int size = in.readVarInt();
		if (size < 0 || size > RecipeTransfer.MAX_LIST_SIZE) throw new IllegalArgumentException("Bad list size " + size);
		return size;
	}

	private void sendResult(Player player, int transferId, boolean success) {
		FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
		try {
			out.writeVarInt(transferId);
			out.writeBoolean(success);
			byte[] bytes = new byte[out.readableBytes()];
			out.readBytes(bytes);
			player.sendPluginMessage(this.plugin, TRANSFER_RESULT, bytes);
		} finally {
			out.release();
		}
	}

	private void sendCheatPermission(Player player) {
		FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
		try {
			out.writeBoolean(false); // has permission
			out.writeVarInt(0); // no cheating methods allowed
			byte[] bytes = new byte[out.readableBytes()];
			out.readBytes(bytes);
			player.sendPluginMessage(this.plugin, CHEAT_PERMISSION, bytes);
		} finally {
			out.release();
		}
	}

	@Override
	public boolean detect(Player player) {
		return player.getListeningPluginChannels().stream().anyMatch(channel -> channel.startsWith("jei:"));
	}

	private boolean isRateLimited(Player player) {
		long now = System.nanoTime();
		Long last = this.lastRequest.get(player.getUniqueId());
		if (last != null && now - last < this.minRequestIntervalNanos) return true;

		// Only an answered request restarts the wait, so a client that keeps asking is still answered at the limit
		this.lastRequest.put(player.getUniqueId(), now);
		return false;
	}

	private void debug(String message) {
		if (this.debug) this.plugin.getLogger().info("[JEI] " + message);
	}
}
