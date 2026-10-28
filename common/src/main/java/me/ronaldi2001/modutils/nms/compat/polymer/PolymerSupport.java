package me.ronaldi2001.modutils.nms.compat.polymer;

import me.ronaldi2001.modutils.nms.ServerApi;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import me.ronaldi2001.modutils.nms.Nms;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.pluginHooks.craftengine.CraftEngineHook;
import me.ronaldi2001.modutils.nms.ItemTranslator;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Talks to clients that have the Polymer mod (Patbox's client side for server-only mods) as if this were a server that
 * runs Polymer, so that those clients can tell a custom item from the plain item it is made of.
 * <p>
 * It starts with a hello, handshake and metadata exchange, then a sync of the custom items, and from then on every
 * item stack that is a custom item carries a marker in its custom data. Recipes that take custom items name them by id (see {@link PolymerHook}).
 * <p>
 * Nothing here is sent to a client that doesn't answer the hello, so clients without the mod are not affected.
 */
public final class PolymerSupport implements PolymerHook {
	public static final PolymerSupport INSTANCE = new PolymerSupport();

	// Channels
	static final Identifier HELLO = id("hello");
	static final Identifier HANDSHAKE = id("handshake");
	static final Identifier METADATA = id("metadata");
	static final Identifier SYNC_STARTED = id("sync/started");
	static final Identifier SYNC_ITEMS = id("sync/items");
	static final Identifier SYNC_FINISHED = id("sync/finished");
	/** The creative tab holding every custom item, which is also how Polymer's JEI plugin finds them. */
	private static final Identifier TAB_ID = Identifier.fromNamespaceAndPath("modcompat", "custom_items");

	private static final int SERVER_PROTOCOL = SharedConstants.getProtocolVersion();

	/**
	 * What differs between the Polymer releases, by the Minecraft version of the client (a client has the Polymer of its
	 * own version): the version of the item and creative tab packets, what the creative tab packets are called, and
	 * whether the tab contents are sent as the older two plain lists of stacks. The other packets have the same layout
	 * in all of them.
	 * <pre>
	 * 1.21.2-1.21.4  (0.10, 0.11)  version 9   item_group  plain lists
	 * 1.21.5         (0.12)        version 11  item_group
	 * 1.21.6-1.21.11 (0.13-0.15)   version 13  item_group
	 * 26.1           (0.16)        version 14  creative_tab
	 * 26.2, 26.3     (0.17, 0.18)  version 15  creative_tab
	 * </pre>
	 *
	 * @param legacyContent the tab contents are two lists of stacks, not lists of entries (version 9)
	 */
	record Profile(int protocol, int base, String tabName, boolean legacyContent) {
		Identifier tabDefine() {
			return id("sync/" + this.tabName + "/define");
		}

		Identifier tabClear() {
			return id("sync/" + this.tabName + "/contents/clear");
		}

		Identifier tabAdd() {
			return id("sync/" + this.tabName + "/contents/add");
		}

		Identifier tabApply() {
			return id("sync/" + this.tabName + "/apply_update");
		}
	}

	/**
	 * @return how to talk to a Polymer client of this Minecraft version (given as its protocol number), or null if it
	 * isn't one we know the packets of
	 */
	static @Nullable Profile profileFor(int protocol) {
		return switch (protocol) {
			case 768, 769 -> new Profile(protocol, 9, "item_group", true);
			case 770 -> new Profile(protocol, 11, "item_group", false);
			case 771, 772, 773, 774 -> new Profile(protocol, 13, "item_group", false);
			case 775 -> new Profile(protocol, 14, "creative_tab", false);
			case 776, 777 -> new Profile(protocol, 15, "creative_tab", false);
			default -> null;
		};
	}

	// The packet versions this implementation writes. A client must support them too.
	private static final int V_HANDSHAKE = 2;
	private static final int V_METADATA = 2;
	private static final int V_SYNC_STARTED = 6;
	private static final int V_SYNC_FINISHED = 6;

	// Metadata keys
	private static final Identifier KEY_PROTOCOL = id("minecraft_protocol");
	private static final Identifier KEY_VERSION = id("minecraft_version");
	private static final Identifier KEY_LIMITED_F3 = id("settings/limited_f3");
	private static final Identifier KEY_EXTENDED_INGREDIENTS = id("core/extended_recipe_ingredients");

	/** What we call ourselves to the client. */
	private static final String SERVER_VERSION = "modcompat";

	/** Entries are sent in batches this big, to stay well below the packet size limit. */
	private static final int ENTRIES_PER_PACKET = 40;

	private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
	private final List<Consumer<Player>> readyCallbacks = new CopyOnWriteArrayList<>();
	private final PolymerItems items = new PolymerItems();

	private final AtomicInteger seenIngredients = new AtomicInteger();
	private final AtomicInteger seenExact = new AtomicInteger();
	private final AtomicInteger sentCustom = new AtomicInteger();
	private final AtomicInteger explained = new AtomicInteger();

	private static final int MODE_INSERT_END = 2;
	/** Recipes are looked through again at most this often. */
	private static final long SCAN_INTERVAL_MS = 30_000L;

	private volatile boolean scanned;
	private volatile long lastScan;
	private volatile String tabName = "Server Items";

	private volatile boolean active;
	private volatile boolean debug;
	private volatile @Nullable Logger logger;
	private volatile @Nullable ModUtilsPlugin plugin;

	private PolymerSupport() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("polymer", path);
	}

	void enable(ModUtilsPlugin plugin, Logger logger, boolean debug, String tabName, PolymerItems.Detection detection,
			   List<IngredientRule> rules) {
		this.plugin = plugin;
		this.logger = logger;
		this.debug = debug;
		this.tabName = tabName;
		this.items.detection(detection);
		this.items.onNew(description -> this.debug("New custom item " + description));
		this.rules(rules);
		this.active = true;
	}

	void disable() {
		this.active = false;
		this.sessions.clear();
	}

	boolean isActive() {
		return this.active;
	}

	PolymerItems items() {
		return this.items;
	}

	void debug(String message) {
		Logger log = this.logger;
		if (this.debug && log != null) log.info("[Polymer] " + message);
	}

	void warn(String message) {
		Logger log = this.logger;
		if (log != null) log.warning("[Polymer] " + message);
	}

	/**
	 * What we know of one connection.
	 */
	static final class Session {
		final UUID id;
		final Channel channel;
		/** Held while the creative tab is sent, so two sends for one player don't interleave. */
		final Object tabLock = new Object();
		/** The packet versions the client announced, by channel. */
		volatile Map<Identifier, int[]> clientVersions = Map.of();
		volatile boolean handshakeSeen;
		/** The client has the mod but can't be talked to (another version of it). */
		volatile boolean failed;
		volatile boolean ready;
		volatile boolean extended;
		/** Set once the player is in the world and has been sent the custom items. Tagging only starts then. */
		volatile boolean synced;
		volatile boolean playing;
		volatile @Nullable Player player;
		/** Version to write each sync packet in. */
		volatile int itemsVersion = -1;
		/** How to talk to this client, by its Minecraft version. */
		volatile @Nullable Profile profile;
		/** For a client on another Minecraft version than the server: writes its items. Null for one on the server's. */
		volatile @Nullable ItemTranslator translator;
		/** Whether the client can take the creative tab with the custom items in it. */
		volatile boolean canTab;
		/** How many custom items the client's tab holds, to know when it is out of date. */
		volatile int tabCount = -1;
		final Map<Integer, Boolean> sentItems = new ConcurrentHashMap<>();

		Session(UUID id, Channel channel) {
			this.id = id;
			this.channel = channel;
		}
	}

	// ------------------------------------------------------------------ connection lifecycle

	/**
	 * A connection is being set up: remember it and ask the client whether it speaks Polymer.
	 */
	void onConnection(UUID id, Channel channel) {
		Session session = new Session(id, channel);
		this.sessions.put(id, session);
		channel.pipeline().addAfter("encoder", PolymerChannel.NAME, new PolymerChannel(this, session));
		this.sendRaw(session, HELLO, versioned(-1, buf -> {
		}));
		this.debug("Sent hello to " + id);
	}

	void onQuit(UUID id) {
		this.sessions.remove(id);
	}

	void onJoin(Player player) {
		Session session = this.sessions.get(player.getUniqueId());
		if (session == null) return;
		session.player = player;
		session.playing = true;
		if (session.ready) this.sync(session);
	}

	// ------------------------------------------------------------------ incoming

	/**
	 * A packet on one of Polymer's channels arrived from the client.
	 */
	void handle(Session session, Identifier channel, byte[] data) {
		try {
			if (channel.equals(HANDSHAKE)) this.handleHandshake(session, data);
			else if (channel.equals(METADATA)) this.handleMetadata(session, data);
			else this.debug("Ignoring " + channel + " from " + session.id);
		} catch (Throwable e) {
			this.warn("Could not read " + channel + " from " + session.id + ": " + e);
		}
	}

	private void handleHandshake(Session session, byte[] data) {
		FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
		try {
			in.readVarInt(); // the version this packet was written in
			String clientVersion = in.readUtf();
			// The version lists are variable-length ints from Polymer for 1.21.11 on, and fixed 4-byte ints before that.
			// Which one this client writes is not known yet (its version comes with its metadata, later), so it is
			// read the newer way first and, if that is not a clean read of the whole packet, the older way.
			int listStart = in.readerIndex();
			Map<Identifier, int[]> versions = readVersionLists(in, false);
			if (versions == null) {
				in.readerIndex(listStart);
				versions = readVersionLists(in, true);
			}
			if (versions == null) throw new IllegalStateException("the handshake's version lists could not be read");
			session.clientVersions = versions;
			session.handshakeSeen = true;
			this.debug(session.id + " answered the hello: Polymer " + clientVersion);

			// Our handshake now; our metadata once the client's has told us which Minecraft version it is
			this.sendRaw(session, HANDSHAKE, versioned(V_HANDSHAKE, buf -> {
				buf.writeUtf(SERVER_VERSION);
				buf.writeVarInt(0); // we have no packets for the client to send us
			}));
		} finally {
			in.release();
		}
	}

	/**
	 * The packet versions a client announces: a count, then for each packet its name and the versions it reads.
	 *
	 * @param fixedInts whether the versions are 4-byte ints (Polymer for 1.21.10 and older) or variable-length ints
	 * @return null if this is not a clean read of the rest of the packet
	 */
	private static @Nullable Map<Identifier, int[]> readVersionLists(FriendlyByteBuf in, boolean fixedInts) {
		try {
			int count = in.readVarInt();
			if (count < 0 || count > 4096) return null;

			Map<Identifier, int[]> versions = new HashMap<>();
			for (int i = 0; i < count; i++) {
				Identifier packet = in.readIdentifier();
				int size = in.readVarInt();
				if (size < 0 || size > 64) return null;

				int[] values = new int[size];
				for (int j = 0; j < size; j++) {
					values[j] = fixedInts ? in.readInt() : in.readVarInt();
					if (values[j] < 0 || values[j] > 10_000) return null;
				}
				versions.put(packet, values);
			}
			return in.isReadable() ? null : versions;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Our metadata. The protocol it names is the one the client reads its sync packets by: a Polymer client skips
	 * them when it differs from its own, so for a client on another version than the server it names the client's.
	 */
	private void sendMetadata(Session session, int protocol) {
		this.sendRaw(session, METADATA, versioned(V_METADATA, buf -> {
			Map<Identifier, Tag> metadata = new HashMap<>();
			metadata.put(KEY_VERSION, StringTag.valueOf(Nms.versionName()));
			metadata.put(KEY_PROTOCOL, IntTag.valueOf(protocol));
			metadata.put(KEY_LIMITED_F3, ByteTag.valueOf(false));
			buf.writeVarInt(metadata.size());
			metadata.forEach((key, value) -> {
				buf.writeIdentifier(key);
				ByteBufCodecs.TAG.encode(buf, value);
			});
		}));
	}

	private void handleMetadata(Session session, byte[] data) {
		FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
		try {
			in.readVarInt();
			int count = in.readVarInt();
			Map<Identifier, Tag> metadata = new HashMap<>();
			for (int i = 0; i < count; i++) {
				Identifier key = in.readIdentifier();
				metadata.put(key, ByteBufCodecs.TAG.decode(in));
			}

			int clientProtocol = metadata.get(KEY_PROTOCOL) instanceof IntTag protocol ? Nms.intValue(protocol) : -1;
			Profile profile = profileFor(clientProtocol);
			// A client on another version than the server can only be given items through ViaVersion's mappings
			ModUtilsPlugin plugin = this.plugin;
			boolean reachable = profile != null
					&& (clientProtocol == SERVER_PROTOCOL || plugin != null && plugin.getViaVersionHook() != null);
			boolean wantsExtended = metadata.get(KEY_EXTENDED_INGREDIENTS) instanceof ByteTag flag && Nms.byteValue(flag) != 0;
			int itemsVersion = profile == null ? -1 : this.negotiate(session, SYNC_ITEMS, profile.base());
			boolean canSync = reachable && itemsVersion != -1
					&& this.negotiate(session, SYNC_STARTED, V_SYNC_STARTED) != -1
					&& this.negotiate(session, SYNC_FINISHED, V_SYNC_FINISHED) != -1;

			this.debug(session.id + " metadata: client protocol " + clientProtocol + " (server " + SERVER_PROTOCOL
					+ "), extended ingredients " + wantsExtended + ", can sync " + canSync);
			if (!canSync) {
				session.failed = true;
				this.sendMetadata(session, SERVER_PROTOCOL);
				this.warn(session.id + " has the Polymer mod but not in a version this plugin can talk to: "
						+ this.whyNot(session, clientProtocol, profile, reachable, itemsVersion));
				return;
			}

			session.profile = profile;
			session.itemsVersion = itemsVersion;
			session.canTab = this.negotiate(session, profile.tabDefine(), profile.base()) != -1
					&& this.negotiate(session, profile.tabClear(), profile.base()) != -1
					&& this.negotiate(session, profile.tabAdd(), profile.base()) != -1
					&& this.negotiate(session, profile.tabApply(), profile.base()) != -1;
			session.extended = wantsExtended;
			this.sendMetadata(session, clientProtocol);
			session.ready = true;
			if (session.playing) this.sync(session);
			else this.notifyReady(session);
		} finally {
			in.release();
		}
	}

	/**
	 * Says what stopped a Polymer client from being synced, for the log.
	 */
	private String whyNot(Session session, int clientProtocol, @Nullable Profile profile, boolean reachable, int itemsVersion) {
		if (profile == null) {
			return "its Minecraft version (protocol " + clientProtocol + ") is not one of 1.21.2 to 26.3 that this plugin knows the packets of";
		}
		if (!reachable) {
			return "it is on protocol " + clientProtocol + " and the server on " + SERVER_PROTOCOL + ", which needs ViaVersion to "
					+ "translate the items, and the plugin has no hook into ViaVersion (it was not loaded when this plugin started)";
		}
		if (itemsVersion == -1) {
			int[] theirs = session.clientVersions.get(SYNC_ITEMS);
			return "its Polymer reads the item sync in version(s) " + (theirs == null ? "none" : java.util.Arrays.toString(theirs))
					+ " but this plugin writes version " + profile.base() + " for protocol " + clientProtocol;
		}
		return "its Polymer does not read the sync start or finish packets in the version this plugin writes (client versions: "
				+ session.clientVersions.entrySet().stream()
				.filter(e -> e.getKey().equals(SYNC_STARTED) || e.getKey().equals(SYNC_FINISHED))
				.map(e -> e.getKey().getPath() + "=" + java.util.Arrays.toString(e.getValue()))
				.collect(java.util.stream.Collectors.joining(", ")) + ")";
	}

	/**
	 * @return the highest version both sides write the packet in, or -1
	 */
	private int negotiate(Session session, Identifier packet, int ours) {
		int[] theirs = session.clientVersions.get(packet);
		if (theirs == null) return -1;
		for (int version : theirs) {
			if (version == ours) return ours;
		}
		return -1;
	}

	// ------------------------------------------------------------------ outgoing

	/**
	 * Tells the client about every custom item and starts tagging stacks.
	 */
	private void sync(Session session) {
		if (session.synced) return;

		Profile profile = session.profile;
		if (profile != null && profile.protocol() != SERVER_PROTOCOL) {
			Player player = session.player;
			ModUtilsPlugin plugin = this.plugin;
			ItemTranslator translator = player == null || plugin == null ? null : ItemTranslator.forPlayer(plugin, player);
			if (translator == null) {
				session.failed = true;
				this.warn("Could not translate items for " + session.id + ": ViaVersion has no mappings for their version");
				return;
			}
			session.translator = translator;
		}

		this.sendRaw(session, SYNC_STARTED, versioned(V_SYNC_STARTED, buf -> {
		}));
		// No SYNC_CLEAR: a fresh join has nothing to forget, and it makes the client add every item twice
		session.sentItems.clear();
		this.sendItems(session, this.items.all());
		this.sendRaw(session, SYNC_FINISHED, versioned(V_SYNC_FINISHED, buf -> {
		}));
		session.synced = true;
		this.debug("Synced " + this.items.all().size() + " custom items to " + session.id);
		this.notifyReady(session);
	}

	private void notifyReady(Session session) {
		Player player = session.player;
		if (player == null) return;
		for (Consumer<Player> callback : this.readyCallbacks) callback.accept(player);
	}

	/**
	 * Sends the entries the client doesn't have yet.
	 */
	void sendItems(Session session, List<PolymerItems.Entry> entries) {
		List<PolymerItems.Entry> missing = new ArrayList<>();
		for (PolymerItems.Entry entry : entries) {
			if (session.sentItems.putIfAbsent(entry.numId(), Boolean.TRUE) == null) missing.add(entry);
		}
		for (int from = 0; from < missing.size(); from += ENTRIES_PER_PACKET) {
			List<PolymerItems.Entry> batch = missing.subList(from, Math.min(missing.size(), from + ENTRIES_PER_PACKET));
			this.sendRaw(session, SYNC_ITEMS, versioned(session.itemsVersion, buf -> {
				RegistryFriendlyByteBuf registryBuf = new RegistryFriendlyByteBuf(buf, MinecraftServer.getServer().registryAccess());
				registryBuf.writeVarInt(batch.size());
				for (PolymerItems.Entry entry : batch) {
					registryBuf.writeVarInt(entry.numId());
					registryBuf.writeIdentifier(entry.id());
					writeStack(registryBuf, session.translator, entry.visual());
				}
			}));
		}
	}

	/**
	 * An optional stack as the client reads it: by the vanilla codec for a client on the server's version, translated
	 * (ids, components) for any other.
	 */
	private static void writeStack(RegistryFriendlyByteBuf buf, @Nullable ItemTranslator translator, ItemStack stack) {
		if (translator == null) {
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
		} else {
			translator.writeStack(buf, stack);
		}
	}

	/**
	 * A packet in the layout every Polymer packet has: the version it is written in, then its contents.
	 */
	private static byte[] versioned(int version, Consumer<FriendlyByteBuf> body) {
		ByteBuf raw = Unpooled.buffer();
		try {
			FriendlyByteBuf buf = new FriendlyByteBuf(raw);
			buf.writeVarInt(version);
			body.accept(buf);
			byte[] bytes = new byte[raw.readableBytes()];
			raw.readBytes(bytes);
			return bytes;
		} finally {
			raw.release();
		}
	}

	private void sendRaw(Session session, Identifier channel, byte[] data) {
		session.channel.writeAndFlush(payload(channel, data));
	}

	static Packet<?> payload(Identifier channel, byte[] data) {
		return Nms.customPayload(channel, data);
	}

	// ------------------------------------------------------------------ PolymerHook

	@Override
	public boolean pending(UUID player) {
		Session session = this.sessions.get(player);
		return this.active && session != null && session.handshakeSeen && !session.failed && !session.synced;
	}

	@Override
	public boolean knowsCustomItems(UUID player) {
		Session session = this.sessions.get(player);
		return this.active && session != null && session.ready && session.synced;
	}

	@Override
	public boolean extendedIngredients(UUID player) {
		Session session = this.sessions.get(player);
		return this.active && session != null && session.ready && session.extended && session.synced;
	}

	@Override
	public void onReady(Consumer<Player> callback) {
		this.readyCallbacks.add(callback);
	}

	@Override
	public void removeReady(Consumer<Player> callback) {
		this.readyCallbacks.remove(callback);
	}

	@Override
	public void ensureSynced(Player player) {
		Session session = this.sessions.get(player.getUniqueId());
		if (session == null || !session.synced) return;
		this.debug("Recipes for " + player.getName() + ": " + this.seenIngredients.get() + " ingredients looked at, "
				+ this.seenExact.get() + " exact, " + this.sentCustom.get() + " written as custom items; "
				+ this.items.all().size() + " custom items known");
		this.scanRecipes();
		this.sendItems(session, this.items.all());
		this.sendTab(session);
	}

	/**
	 * Registers every custom item the recipes mention (as a result or as an input), so they are all known to the
	 * client, and searchable in JEI, not only the ones that happen to be in a stack it has seen.
	 */
	private void scanRecipes() {
		long now = System.currentTimeMillis();
		if (now - this.lastScan < SCAN_INTERVAL_MS && this.scanned) return;
		this.scanned = true;
		this.lastScan = now;

		for (RecipeHolder<?> holder : MinecraftServer.getServer().getRecipeManager().getRecipes()) {
			try {
				this.items.identify(CraftItemStack.asNMSCopy(holder.toBukkitRecipe().getResult()));
				for (Ingredient ingredient : this.ingredientsOf(holder.value(), "")) {
					if (ingredient == null) continue;
					List<ItemStack> exact = exactStacks(ingredient);
					if (exact != null) {
						for (ItemStack stack : exact) this.items.identify(stack);
					} else {
						this.overrideIds(ingredient, Nms.recipeId(holder).toString());
					}
				}
			} catch (Throwable e) {
				// A recipe that can't be looked at is just left out
				if (this.debug && this.explained.getAndIncrement() < 10) {
					this.debug("Could not look at recipe " + Nms.recipeId(holder) + ": " + e);
				}
			}
		}
	}

	void invalidateScan() {
		this.scanned = false;
	}

	/**
	 * Gives the client a creative tab with every custom item. Polymer's JEI plugin takes the items it adds to JEI's
	 * item list from the creative tabs, which is what makes them searchable there.
	 */
	private void sendTab(Session session) {
		// Clear and add must not interleave with another send for the same player, or items would be added twice
		synchronized (session.tabLock) {
			this.sendTabLocked(session);
		}
	}

	private void sendTabLocked(Session session) {
		List<PolymerItems.Entry> entries = this.items.all();
		if (!session.canTab || entries.isEmpty() || entries.size() == session.tabCount) return;
		session.tabCount = entries.size();

		// Items that look the same are one entry in the tab, even if their data differs
		Map<String, ItemStack> byLook = new java.util.LinkedHashMap<>();
		for (PolymerItems.Entry entry : entries) byLook.putIfAbsent(this.items.lookKey(entry.visual()), entry.visual());
		List<ItemStack> stacks = new ArrayList<>(byLook.values());
		ItemTranslator translator = session.translator;
		Profile profile = session.profile;
		if (translator != null) {
			// Items the client has no equivalent of can't be in its tab
			stacks.removeIf(stack -> translator.translateItemId(BuiltInRegistries.ITEM.getId(stack.getItem())) < 0);
		}
		if (profile == null || stacks.isEmpty()) return;
		stacks.sort(java.util.Comparator.comparing(stack -> stack.getHoverName().getString()));
		int version = profile.base();

		this.sendRaw(session, profile.tabDefine(), versioned(version, buf -> {
			RegistryFriendlyByteBuf registryBuf = new RegistryFriendlyByteBuf(buf, MinecraftServer.getServer().registryAccess());
			registryBuf.writeIdentifier(TAB_ID);
			ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(registryBuf, Component.literal(this.tabName));
			// The icon is not optional: the first stack, which the client has an equivalent of
			if (translator == null) {
				ItemStack.STREAM_CODEC.encode(registryBuf, stacks.getFirst());
			} else {
				translator.writeStack(registryBuf, stacks.getFirst());
			}
		}));
		this.sendRaw(session, profile.tabClear(), versioned(version, buf -> buf.writeIdentifier(TAB_ID)));
		this.sendRaw(session, profile.tabAdd(), versioned(version, buf -> {
			RegistryFriendlyByteBuf registryBuf = new RegistryFriendlyByteBuf(buf, MinecraftServer.getServer().registryAccess());
			registryBuf.writeIdentifier(TAB_ID);
			// Two lists, the tab's own and the search. Each is one "insert at the end" entry with all the items, or, in
			// the older layout, just the items
			for (int list = 0; list < 2; list++) {
				if (!profile.legacyContent()) {
					registryBuf.writeVarInt(1);
					registryBuf.writeVarInt(MODE_INSERT_END);
				}
				registryBuf.writeVarInt(stacks.size());
				for (ItemStack stack : stacks) writeStack(registryBuf, translator, stack);
			}
		}));
		this.sendRaw(session, profile.tabApply(), versioned(version, buf -> {
		}));
		this.debug("Sent the creative tab with " + stacks.size() + " custom items to " + session.id
				+ (stacks.size() != entries.size() ? " (" + entries.size() + " registered, the rest look the same)" : ""));
	}

	@Override
	public ItemStack markResult(ItemStack stack) {
		PolymerItems.Entry entry = this.items.identify(stack);
		return entry == null ? stack : PolymerItems.tag(stack, entry);
	}

	@Override
	public @Nullable List<Integer> polymerIds(Ingredient ingredient, String recipeKey) {
		this.seenIngredients.incrementAndGet();
		List<ItemStack> exact = exactStacks(ingredient);
		if (exact == null) return this.overrideIds(ingredient, recipeKey);
		this.seenExact.incrementAndGet();

		List<Integer> ids = new ArrayList<>();
		for (ItemStack stack : exact) {
			PolymerItems.Entry entry = this.items.identify(stack);
			// One plain stack among them: leave the ingredient as the server wrote it
			if (entry == null) {
				if (this.debug && this.explained.getAndIncrement() < 10) {
					this.debug("Exact ingredient not sent as a custom item, it has no item_model or custom_model_data: "
							+ stack.getItem() + " with " + stack.getComponentsPatch());
				}
				return this.overrideIds(ingredient, recipeKey);
			}
			ids.add(entry.numId());
		}
		if (ids.isEmpty()) return null;
		this.sentCustom.incrementAndGet();
		return ids;
	}

	/**
	 * @return the stacks an ingredient takes if it matches items by their components (Paper's exact choice, which is
	 * how plugins make a recipe take a custom item), or null for an ingredient that is a plain set of items
	 */
	static @Nullable List<ItemStack> exactStacks(Ingredient ingredient) {
		return Nms.exactStacks(ingredient);
	}

	/**
	 * A plain item that the config says stands for a custom item in some recipes (see the polymer section).
	 */
	private @Nullable List<Integer> overrideIds(Ingredient ingredient, String recipeKey) {
		for (IngredientRule rule : this.rules) {
			if (!rule.recipes().matcher(recipeKey).matches()) continue;
			if (!Nms.items(ingredient).allMatch(holder -> BuiltInRegistries.ITEM.getKey(holder.value()).toString().equals(rule.item()))) {
				continue;
			}

			ItemStack like = this.likeStack(rule);
			PolymerItems.Entry entry = like == null ? null : this.items.identify(like);
			if (entry == null) {
				if (this.debug && this.explained.getAndIncrement() < 10) {
					this.debug("Ingredient rule for " + rule.item() + " in " + recipeKey + ": the item of recipe '" + rule.like()
							+ "' was not found or is not a custom item");
				}
				continue;
			}
			this.sentCustom.incrementAndGet();
			return List.of(entry.numId());
		}
		return this.craftEngineIds(ingredient, recipeKey);
	}

	/**
	 * For a CraftEngine recipe, the custom items its ingredients really take. CraftEngine builds a recipe out of plain
	 * stand-in items (the base material of the custom item), so no rule is needed: the ingredient whose plain items are
	 * the base items of CraftEngine's custom items is shown as those.
	 */
	private @Nullable List<Integer> craftEngineIds(Ingredient ingredient, String recipeKey) {
		CraftEngineHook craftEngine = this.craftEngine();
		if (craftEngine == null) return null;

		List<List<String>> wanted = craftEngine.recipeIngredients(recipeKey);
		if (wanted.isEmpty()) return null;

		java.util.Set<net.minecraft.world.item.Item> plain = new java.util.HashSet<>();
		Nms.items(ingredient).forEach(holder -> plain.add(holder.value()));
		for (List<String> itemIds : wanted) {
			List<Integer> ids = new ArrayList<>();
			for (String itemId : itemIds) {
				ItemStack stack = this.craftEngineStack(itemId);
				PolymerItems.Entry entry = stack == null || !plain.contains(stack.getItem()) ? null : this.items.identify(stack);
				if (entry == null) {
					ids.clear();
					break;
				}
				ids.add(entry.numId());
			}
			if (ids.isEmpty()) continue;
			this.sentCustom.incrementAndGet();
			return ids;
		}
		return null;
	}

	private @Nullable ItemStack craftEngineStack(String itemId) {
		ItemStack known = this.likeStacks.get(itemId);
		if (known != null) return known;

		ModUtilsPlugin plugin = this.plugin;
		if (plugin == null) return null;

		org.bukkit.inventory.ItemStack bukkit = plugin.getCustomItem(itemId);
		if (bukkit == null) return null;

		ItemStack stack = CraftItemStack.asNMSCopy(bukkit);
		this.likeStacks.put(itemId, stack);
		return stack;
	}

	private @Nullable CraftEngineHook craftEngine() {
		ModUtilsPlugin plugin = this.plugin;
		return plugin == null ? null : plugin.getCraftEngineHook();
	}

	private @Nullable ItemStack likeStack(IngredientRule rule) {
		ItemStack known = this.likeStacks.get(rule.like());
		if (known != null) return known;

		NamespacedKey key = NamespacedKey.fromString(rule.like());
		org.bukkit.inventory.Recipe recipe = key == null ? null : Bukkit.getRecipe(key);
		// Not a recipe: a CraftEngine item id works too
		if (recipe == null) return this.craftEngineStack(rule.like());

		ItemStack stack = CraftItemStack.asNMSCopy(recipe.getResult());
		this.likeStacks.put(rule.like(), stack);
		return stack;
	}

	/**
	 * @param recipes which recipes it applies to (a pattern on the recipe key)
	 * @param item    the plain item, e.g. {@code minecraft:leather_horse_armor}
	 * @param like    the recipe whose result the item stands for
	 */
	record IngredientRule(java.util.regex.Pattern recipes, String item, String like) {
	}

	private volatile List<IngredientRule> rules = List.of();
	private final Map<String, ItemStack> likeStacks = new ConcurrentHashMap<>();

	void rules(List<IngredientRule> rules) {
		this.rules = rules;
		this.likeStacks.clear();
	}

	@Override
	public List<@Nullable Ingredient> ingredientsOf(Recipe<?> recipe, String serializer) {
		return Nms.ingredientsOf(recipe);
	}

	// ------------------------------------------------------------------ item stacks

	/**
	 * @return the stack as the Polymer client should see it, or the same one if it is not a custom item
	 */
	ItemStack tag(Session session, ItemStack stack) {
		if (stack.isEmpty()) return stack;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data != null && ServerApi.hasKey(data, PolymerItems.STACK_KEY)) return stack;

		PolymerItems.Entry entry = this.items.identify(stack);
		if (entry == null) return stack;

		// The client has to know the item before it sees a stack of it
		this.sendItems(session, List.of(entry));
		return PolymerItems.tag(stack, entry);
	}

	/**
	 * Undoes {@link #tag}, for stacks the client sends back.
	 */
	ItemStack untag(ItemStack stack) {
		return PolymerItems.untag(stack);
	}

	@Nullable Session session(UUID id) {
		return this.sessions.get(id);
	}
}
