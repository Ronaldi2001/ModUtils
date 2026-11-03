package me.ronaldi2001.modutils.nms.compat.shulkerboxtooltip;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.modCompat.api.ModCompat;
import me.ronaldi2001.modutils.nms.ItemTranslator;
import me.ronaldi2001.modutils.pluginHooks.via.ViaVersionHook;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * ShulkerBoxTooltip: shows what is in shulker boxes (and other containers) when hovering over them. That works on the
 * client alone, from the item's own data. What a server adds is the player's ender chest, which the client has no way to
 * know until it is opened, so the preview of an ender chest item has nothing to show.
 * <p>
 * Channels (protocol 2.0, the same on every version of the mod from 1.20.x to 26.3, Fabric and NeoForge), all in the
 * {@code shulkerboxtooltip} namespace:
 * <ul>
 *   <li>{@code c2s_handshake}, from the client: its protocol version, two ints. A client sends it once the server says it
 *   can take it, which is when this registers the channel.</li>
 *   <li>{@code s2c_handshake}, the answer: the server's protocol version (two ints) and the settings the server decides,
 *   as an NBT compound ({@code server.clientIntegration}, {@code server.enderChestSyncType}).</li>
 *   <li>{@code ec_update}, from the server: an NBT compound with the ender chest as the list {@code inv}, each slot a
 *   compound with {@code Slot}, {@code id}, {@code count} and {@code components}, as the game saves a container.</li>
 *   <li>{@code ec_update_req}, from the client: no data, asks for an {@code ec_update}. Sent when the client wants the
 *   ender chest and the sync type is passive.</li>
 * </ul>
 * With the sync type active the ender chest goes out after every change, with passive only when asked for. (The mod also
 * has a type that sends nothing; switching the compat off does that.)
 * <p>
 * Everything is sent for the player's own ender chest only. A client on another version than the server (through
 * ViaVersion) gets each stack as its item, named the way that version names it, and its count: the data of an item
 * (enchantments, names, ...) is written differently by every version and can't be translated, so those previews show
 * plain items.
 */
public class ShulkerBoxTooltipCompat extends ModCompat implements PluginMessageListener {
	private static final String NAMESPACE = "shulkerboxtooltip";
	private static final String HANDSHAKE_START = NAMESPACE + ":c2s_handshake";
	private static final String HANDSHAKE_RESPONSE = NAMESPACE + ":s2c_handshake";
	private static final String UPDATE_REQUEST = NAMESPACE + ":ec_update_req";
	private static final String UPDATE = NAMESPACE + ":ec_update";
	private static final String[] INCOMING = {HANDSHAKE_START, UPDATE_REQUEST};
	private static final String[] OUTGOING = {HANDSHAKE_RESPONSE, UPDATE};

	/**
	 * The protocol version this speaks. Clients with another major version can't be talked to.
	 */
	private static final int PROTOCOL_MAJOR = 2;
	private static final int PROTOCOL_MINOR = 0;
	/**
	 * Ticks to wait after a change in an ender chest before sending it: several changes in a row (a drag, a shift-click)
	 * go out together, and the game has applied the change by then.
	 */
	private static final long CHANGE_DELAY_TICKS = 5L;
	/**
	 * The least time between two requests from a player that are answered.
	 */
	private static final long MIN_REQUEST_INTERVAL_NANOS = 250_000_000L;

	/**
	 * How a server tells its clients to keep the ender chest up to date.
	 */
	private enum SyncType {
		ACTIVE, PASSIVE
	}

	/**
	 * Players that completed the handshake.
	 */
	private final Set<UUID> clients = ConcurrentHashMap.newKeySet();
	/**
	 * Players whose ender chest is about to go out, after a change.
	 */
	private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
	private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();
	private SyncType syncType = SyncType.ACTIVE;
	private boolean debug;

	public ShulkerBoxTooltipCompat(ModUtilsPlugin plugin) {
		super(plugin);
	}

	@Override
	public String getConfigKey() {
		return "shulkerBoxTooltip";
	}

	@Override
	public String getName() {
		return "ShulkerBoxTooltip";
	}

	@Override
	public void onEnable(ConfigurationSection section) {
		this.debug = section.getBoolean("debug", false);
		this.syncType = this.parseSyncType(section.getString("enderChestSync", "ACTIVE"));

		Messenger messenger = this.plugin.getServer().getMessenger();
		for (String channel : INCOMING) messenger.registerIncomingPluginChannel(this.plugin, channel, this);
		for (String channel : OUTGOING) messenger.registerOutgoingPluginChannel(this.plugin, channel);
	}

	@Override
	public void onDisable() {
		Messenger messenger = this.plugin.getServer().getMessenger();
		for (String channel : INCOMING) messenger.unregisterIncomingPluginChannel(this.plugin, channel, this);
		for (String channel : OUTGOING) messenger.unregisterOutgoingPluginChannel(this.plugin, channel);
		this.clients.clear();
		this.pending.clear();
		this.lastRequest.clear();
	}

	@Override
	public boolean detect(Player player) {
		return player.getListeningPluginChannels().contains(HANDSHAKE_RESPONSE);
	}

	private SyncType parseSyncType(String value) {
		try {
			return SyncType.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			this.plugin.getLogger().warning("[ShulkerBoxTooltip] enderChestSync must be ACTIVE or PASSIVE, not '"
					+ value + "'; using ACTIVE");
			return SyncType.ACTIVE;
		}
	}

	@Override
	public void onPluginMessageReceived(@NonNull String channel, @NonNull Player player, byte @NonNull [] message) {
		if (this.plugin.getBedrockHook().isBedrock(player)) return;

		if (channel.equals(HANDSHAKE_START)) {
			this.onHandshake(player, message);
		} else if (channel.equals(UPDATE_REQUEST)) {
			this.onUpdateRequest(player);
		}
	}

	/**
	 * Answers the handshake: the server's version and settings, then the ender chest. A client with another major version
	 * gets no answer, which makes it give up on the server.
	 */
	private void onHandshake(Player player, byte[] message) {
		ByteBuf raw = Unpooled.wrappedBuffer(message);
		try {
			if (raw.readableBytes() < 8) return;
			int major = raw.readInt();
			int minor = raw.readInt();
			this.debug(player.getName() + " sent a handshake, protocol " + major + "." + minor);
			if (major != PROTOCOL_MAJOR) {
				this.debug("Not answering " + player.getName() + ": protocol major " + PROTOCOL_MAJOR + " is the one spoken");
				return;
			}
		} finally {
			raw.release();
		}

		this.plugin.getScheduler().runOnEntity(player, () -> {
			if (!player.isOnline()) return;

			this.clients.add(player.getUniqueId());
			this.sendHandshakeResponse(player);
			this.sendEnderChest(player);
		});
	}

	private void onUpdateRequest(Player player) {
		if (!this.clients.contains(player.getUniqueId())) return;

		long now = System.nanoTime();
		Long last = this.lastRequest.put(player.getUniqueId(), now);
		if (last != null && now - last < MIN_REQUEST_INTERVAL_NANOS) return;

		this.plugin.getScheduler().runOnEntity(player, () -> {
			if (player.isOnline()) this.sendEnderChest(player);
		});
	}

	private void sendHandshakeResponse(Player player) {
		CompoundTag server = new CompoundTag();
		server.putBoolean("clientIntegration", true);
		server.putString("enderChestSyncType", this.syncType.name());
		CompoundTag config = new CompoundTag();
		config.put("server", server);

		FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
		try {
			out.writeInt(PROTOCOL_MAJOR);
			out.writeInt(PROTOCOL_MINOR);
			out.writeNbt(config);
			this.send(player, HANDSHAKE_RESPONSE, out);
		} finally {
			out.release();
		}
		this.debug("Answered the handshake of " + player.getName());
	}

	/**
	 * Sends the player's own ender chest. Empty slots are left out, as the game does when it saves a container.
	 */
	private void sendEnderChest(Player player) {
		// A client on another version (through ViaVersion) can't read the data of an item as this version writes it
		ViaVersionHook via = this.plugin.getViaVersionHook();
		boolean otherVersion = via != null && via.getPlayerVersion(player) != SharedConstants.getProtocolVersion();
		ItemTranslator translator = otherVersion ? ItemTranslator.forPlayer(this.plugin, player) : null;
		if (otherVersion && translator == null) {
			this.debug("Not sending the ender chest to " + player.getName() + ": no way to translate it for their version");
			return;
		}

		ServerPlayer handle = ((CraftPlayer) player).getHandle();
		Container chest = handle.getEnderChestInventory();
		var context = handle.registryAccess().createSerializationContext(NbtOps.INSTANCE);

		ListTag items = new ListTag();
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			ItemStack stack = chest.getItem(slot);
			if (stack.isEmpty()) continue;

			Tag encoded = translator == null
					? ItemStack.CODEC.encodeStart(context, stack).result().orElse(null)
					: this.plainStack(stack, translator);
			if (!(encoded instanceof CompoundTag tag)) continue;

			tag.putByte("Slot", (byte) slot);
			items.add(tag);
		}
		CompoundTag root = new CompoundTag();
		root.put("inv", items);

		FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
		try {
			out.writeNbt(root);
			this.send(player, UPDATE, out);
		} catch (RuntimeException e) {
			this.plugin.getLogger().log(Level.WARNING, "[ShulkerBoxTooltip] Could not send the ender chest of " + player.getName(), e);
			return;
		} finally {
			out.release();
		}
		this.debug("Sent the ender chest of " + player.getName() + " (" + items.size() + " stacks)");
	}

	/**
	 * A stack for a client on another version: the item as that version names it, and the count, without the item's data.
	 * The data (enchantments, names, ...) is written differently by every version, so the preview shows plain items.
	 *
	 * @return null if the client's version has no such item
	 */
	private @Nullable CompoundTag plainStack(ItemStack stack, ItemTranslator translator) {
		String name = translator.translateItemName(BuiltInRegistries.ITEM.getId(stack.getItem()));
		if (name == null) return null;

		CompoundTag tag = new CompoundTag();
		tag.putString("id", name);
		tag.putInt("count", stack.getCount());
		return tag;
	}

	private void send(Player player, String channel, FriendlyByteBuf buf) {
		byte[] bytes = new byte[buf.readableBytes()];
		buf.readBytes(bytes);
		player.sendPluginMessage(this.plugin, channel, bytes);
	}

	/**
	 * With the sync type active: sends the ender chest soon after it changed.
	 */
	private void onEnderChestChange(@Nullable Inventory inventory, Player viewer) {
		if (this.syncType != SyncType.ACTIVE || inventory == null || inventory.getType() != InventoryType.ENDER_CHEST) return;

		// A moderator can open another player's ender chest: it is its owner that needs the update
		Player owner = inventory.getHolder() instanceof Player holder ? holder : viewer;
		UUID id = owner.getUniqueId();
		if (!this.clients.contains(id) || !this.pending.add(id)) return;

		this.plugin.getScheduler().runOnEntityDelayed(owner, () -> {
			this.pending.remove(id);
			if (owner.isOnline()) this.sendEnderChest(owner);
		}, CHANGE_DELAY_TICKS);
	}

	@EventHandler
	public void onClick(InventoryClickEvent event) {
		if (event.getWhoClicked() instanceof Player player) this.onEnderChestChange(event.getView().getTopInventory(), player);
	}

	@EventHandler
	public void onDrag(InventoryDragEvent event) {
		if (event.getWhoClicked() instanceof Player player) this.onEnderChestChange(event.getView().getTopInventory(), player);
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		if (event.getPlayer() instanceof Player player) this.onEnderChestChange(event.getView().getTopInventory(), player);
	}

	/**
	 * The mod does the same on its own server: the client may have lost the contents with the change of world.
	 */
	@EventHandler
	public void onChangeWorld(PlayerChangedWorldEvent event) {
		Player player = event.getPlayer();
		if (!this.clients.contains(player.getUniqueId())) return;

		this.plugin.getScheduler().runOnEntity(player, () -> {
			if (player.isOnline()) this.sendEnderChest(player);
		});
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		UUID id = event.getPlayer().getUniqueId();
		this.clients.remove(id);
		this.pending.remove(id);
		this.lastRequest.remove(id);
	}

	private void debug(String message) {
		if (this.debug) this.plugin.getLogger().info("[ShulkerBoxTooltip] " + message);
	}
}
