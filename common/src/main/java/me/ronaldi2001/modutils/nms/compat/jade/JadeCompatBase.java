package me.ronaldi2001.modutils.nms.compat.jade;

import io.netty.buffer.Unpooled;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.modCompat.api.ModCompat;
import me.ronaldi2001.modutils.nms.ItemWriterCache;
import me.ronaldi2001.modutils.nms.Nms;
import me.ronaldi2001.modutils.nms.compat.jade.providers.BlockProviders;
import me.ronaldi2001.modutils.nms.compat.jade.providers.EntityProviders;
import me.ronaldi2001.modutils.nms.compat.jade.providers.ServiceStorageSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerUnregisterChannelEvent;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * What every version's Jade compat does: lists the server's data providers for the client, answers block and entity
 * requests by running the requested providers, and sends the result back as a {@code jade:receive_data} compound tag.
 * A version supplies how its Jade says hello and how its requests are laid out; the providers themselves are shared.
 * <p>
 * Requests carry indices into the provider list we gave the client, so the list is built once on enable (filtered by
 * the config) and only changes with it.
 */
public abstract class JadeCompatBase extends ModCompat implements PluginMessageListener {
	protected static final String RECEIVE_DATA = "jade:receive_data";

	/**
	 * Matches Jade's own cap; larger replies are trimmed.
	 */
	private static final int MAX_REPLY_BYTES = 16 * 1024;
	private static final double ENTITY_EXTRA_RANGE = 21;

	// Filtered by config on enable
	protected final List<JadeBlockProvider> blockProviders = new ArrayList<>();
	protected final List<JadeEntityProvider> entityProviders = new ArrayList<>();
	private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();
	private final ItemWriterCache itemWriters;
	private long minRequestIntervalNanos;
	protected boolean debug;
	/** The {@code features} section of the config, or null if it has none. */
	protected ConfigurationSection features;
	/** The custom storages that have their items outside vanilla containers. */
	protected List<BlockItemSource> itemSources = List.of();

	protected JadeCompatBase(ModUtilsPlugin plugin) {
		super(plugin);
		this.itemWriters = new ItemWriterCache(plugin);
	}

	/**
	 * @return the channels only a Jade client registers (they are the ones the server sends to first), which is also how
	 * a player is known to have the mod
	 */
	protected abstract String[] presenceChannels();

	/**
	 * @return the channels the client sends requests on
	 */
	protected abstract String[] incomingChannels();

	/**
	 * @return the channels the server sends on, {@link #RECEIVE_DATA} included
	 */
	protected abstract String[] outgoingChannels();

	/**
	 * A message arrived from a player with the mod.
	 *
	 * @param in the message, readable with registry access
	 */
	protected abstract void handle(String channel, Player player, ServerPlayer handle, RegistryFriendlyByteBuf in);

	/**
	 * The client registered one of the {@link #presenceChannels}, so it can be talked to now.
	 *
	 * @param channel the one it registered
	 */
	protected abstract void clientReady(Player player, ServerPlayer handle, String channel);

	/**
	 * A player left, or took Jade away: forget anything kept for them.
	 */
	protected void forget(UUID id) {
	}

	/**
	 * Whether the player is close enough to the block to be answered: the way a player reaches it, with some room. The
	 * Jade of 1.21 and 1.21.1 measures from the feet of the player and allows more.
	 */
	private static boolean blockInReach(ServerPlayer player, BlockPos pos, boolean beforeJade7) {
		if (beforeJade7) return pos.distSqr(player.blockPosition()) <= Mth.square(player.blockInteractionRange() + 21);
		return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= Mth.square(player.blockInteractionRange() + 3);
	}

	@Override
	public String getConfigKey() {
		return "jade";
	}

	@Override
	public String getName() {
		return "Jade";
	}

	@Override
	public void onEnable(ConfigurationSection section) {
		this.minRequestIntervalNanos = Math.max(0L, section.getLong("minRequestIntervalMs", 50L)) * 1_000_000L;
		this.debug = section.getBoolean("debug", false);

		ConfigurationSection features = section.getConfigurationSection("features");
		this.features = features;
		this.blockProviders.clear();
		this.entityProviders.clear();
		List<BlockItemSource> sources = Stream.of(new ServiceStorageSource())
				.filter(source -> isEnabled(features, source.configKey()))
				.map(source -> (BlockItemSource) source)
				.toList();
		this.itemSources = sources;
		BlockProviders.all(sources).stream().filter(p -> isEnabled(features, p.configKey())).forEach(this.blockProviders::add);
		EntityProviders.all().stream().filter(p -> isEnabled(features, p.configKey())).forEach(this.entityProviders::add);

		Messenger messenger = this.plugin.getServer().getMessenger();
		for (String channel : this.incomingChannels()) {
			messenger.registerIncomingPluginChannel(this.plugin, channel, this);
		}
		for (String channel : this.outgoingChannels()) {
			messenger.registerOutgoingPluginChannel(this.plugin, channel);
		}
	}

	@Override
	public void onDisable() {
		Messenger messenger = this.plugin.getServer().getMessenger();
		for (String channel : this.incomingChannels()) {
			messenger.unregisterIncomingPluginChannel(this.plugin, channel, this);
		}
		for (String channel : this.outgoingChannels()) {
			messenger.unregisterOutgoingPluginChannel(this.plugin, channel);
		}
		this.lastRequest.clear();
		this.itemWriters.clear();
	}

	@EventHandler
	public void onUnregisterChannel(PlayerUnregisterChannelEvent event) {
		if (this.isPresenceChannel(event.getChannel())) this.forgetPlayer(event.getPlayer().getUniqueId());
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		this.forgetPlayer(event.getPlayer().getUniqueId());
	}

	private void forgetPlayer(UUID id) {
		this.lastRequest.remove(id);
		this.itemWriters.remove(id);
		this.forget(id);
	}

	/**
	 * Jade's client sends or expects its first packet the moment it joins, which can beat the server's channel list to
	 * it and get dropped. The client registers its channel once it is ready, so that is the time to speak first.
	 */
	@EventHandler
	public void onRegisterChannel(PlayerRegisterChannelEvent event) {
		if (!this.isPresenceChannel(event.getChannel())) return;

		Player player = event.getPlayer();
		if (this.plugin.getBedrockHook().isBedrock(player)) return;
		this.debug("Client of " + player.getName() + " registered Jade channels");
		this.clientReady(player, ((CraftPlayer) player).getHandle(), event.getChannel());
	}

	private boolean isPresenceChannel(String channel) {
		for (String presence : this.presenceChannels()) {
			if (presence.equals(channel)) return true;
		}
		return false;
	}

	@Override
	public boolean detect(Player player) {
		for (String presence : this.presenceChannels()) {
			if (player.getListeningPluginChannels().contains(presence)) return true;
		}
		return false;
	}

	/** Whether the feature is on in the config (on if the config does not say). */
	protected boolean enabled(String key) {
		return isEnabled(this.features, key);
	}

	protected static boolean isEnabled(ConfigurationSection features, String key) {
		return features == null || features.getBoolean(key, true);
	}

	protected void debug(String message) {
		if (this.debug) this.plugin.getLogger().info("[Jade] " + message);
	}

	@Override
	@SuppressWarnings("resource") // ServerLevel is closeable, but it belongs to the server
	public void onPluginMessageReceived(@NonNull String channel, @NonNull Player player, byte @NonNull [] message) {
		if (this.plugin.getBedrockHook().isBedrock(player)) return;
		// Only players whose client registered Jade's channels have the mod; ignore anything else
		if (!this.detect(player)) return;

		this.debug("Received " + channel + " from " + player.getName() + " (" + message.length + " bytes)");
		try {
			ServerPlayer handle = ((CraftPlayer) player).getHandle();
			RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(message), Nms.level(handle).registryAccess());
			try {
				this.handle(channel, player, handle, in);
			} finally {
				in.release();
			}
		} catch (Exception e) {
			this.plugin.getLogger().log(Level.WARNING, "Failed to handle Jade packet " + channel + " from " + player.getName(), e);
		}
	}

	/**
	 * Writes the ids of the providers the client may ask for, blocks then entities, each as a count and the ids.
	 */
	protected void writeProviderIds(RegistryFriendlyByteBuf out) {
		out.writeVarInt(this.blockProviders.size());
		this.blockProviders.forEach(provider -> Identifier.STREAM_CODEC.encode(out, provider.id()));
		out.writeVarInt(this.entityProviders.size());
		this.entityProviders.forEach(provider -> Identifier.STREAM_CODEC.encode(out, provider.id()));
	}

	/**
	 * Reads provider indices relative to the list we gave the client: a count, then the indices.
	 */
	protected <T> List<T> readProviders(FriendlyByteBuf in, List<T> known) {
		int count = in.readVarInt();
		List<T> result = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int index = in.readVarInt();
			if (index >= 0 && index < known.size()) result.add(known.get(index));
		}
		return result;
	}

	/**
	 * Runs the requested block providers on the thread that owns the block and sends the result.
	 *
	 * @param tag     what the providers add to, and what is sent back (already holding whatever the client sent along)
	 * @param beforeJade7 whether the client is on the Jade of 1.21 or 1.21.1
	 */
	@SuppressWarnings("resource") // ServerLevel is closeable, but it belongs to the server
	protected void processBlock(Player player, ServerPlayer handle, BlockPos pos, boolean showDetails, CompoundTag tag,
								boolean beforeJade7, Consumer<BlockCtx> fill) {
		if (this.isRateLimited(player)) return;

		Location location = new Location(Nms.level(handle).getWorld(), pos.getX(), pos.getY(), pos.getZ());
		this.plugin.getScheduler().runAtLocation(
				location, () -> {
					if (!player.isOnline()) return;

					ServerLevel level = Nms.level(handle);
					tag.putInt("x", pos.getX());
					tag.putInt("y", pos.getY());
					tag.putInt("z", pos.getZ());

					if (!blockInReach(handle, pos, beforeJade7) || !level.isLoaded(pos)) {
						// The newer Jade is answered with only the position; the older one gets no answer
						if (!beforeJade7) this.sendData(player, tag);
						return;
					}

					BlockState state = level.getBlockState(pos);
					BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
					tag.putString("BlockId", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());

					fill.accept(new BlockCtx(handle, level, pos, state, blockEntity, showDetails, tag, this.itemWriterFor(player)));
					this.sendData(player, tag);
				}
		);
	}

	/**
	 * Runs the requested entity providers on the thread that owns the entity and sends the result.
	 *
	 * @param tag     what the providers add to, and what is sent back (already holding whatever the client sent along)
	 * @param beforeJade7 whether the client is on the Jade of 1.21 or 1.21.1
	 */
	@SuppressWarnings("resource") // ServerLevel is closeable, but it belongs to the server
	protected void processEntity(Player player, ServerPlayer handle, int entityId, boolean showDetails, CompoundTag tag,
								 boolean beforeJade7, Consumer<EntityCtx> fill) {
		if (this.isRateLimited(player)) return;

		// The lookup runs where the player is, not on the network thread; the entity itself is then read on its own thread
		this.plugin.getScheduler().runOnEntity(player, () -> {
			Entity target = Nms.level(handle).getEntity(entityId);
			if (target == null) return;

			this.plugin.getScheduler().runOnEntity(
					target.getBukkitEntity(), () -> {
						if (!player.isOnline() || target.level() != Nms.level(handle)) return;

						double reach = Mth.square(handle.entityInteractionRange() + ENTITY_EXTRA_RANGE);
						if (handle.distanceToSqr(target) > reach) return;

						fill.accept(new EntityCtx(handle, Nms.level(handle), target, showDetails, tag, this.itemWriterFor(player), beforeJade7));
						tag.putInt("EntityId", target.getId());
						this.sendData(player, tag);
					}
			);
		});
	}

	/**
	 * Runs the requested block providers, and the ones that answer unasked, for {@link #processBlock}.
	 */
	protected Consumer<BlockCtx> runBlockProviders(List<JadeBlockProvider> requested) {
		return ctx -> {
			List<JadeBlockProvider> toRun = new ArrayList<>(requested);
			this.blockProviders.stream().filter(p -> p.alwaysRun() && !requested.contains(p)).forEach(toRun::add);
			for (JadeBlockProvider provider : toRun) {
				try {
					provider.append(ctx);
				} catch (Exception e) {
					this.plugin.getLogger().log(Level.WARNING, "Jade block provider " + provider.id() + " failed", e);
				}
			}
		};
	}

	/**
	 * Runs the requested entity providers, for {@link #processEntity}.
	 */
	protected Consumer<EntityCtx> runEntityProviders(List<JadeEntityProvider> requested) {
		return ctx -> {
			for (JadeEntityProvider provider : requested) {
				try {
					provider.append(ctx);
				} catch (Exception e) {
					this.plugin.getLogger().log(Level.WARNING, "Jade entity provider " + provider.id() + " failed", e);
				}
			}
		};
	}

	private ItemWriter itemWriterFor(Player player) {
		return this.itemWriters.forPlayer(player);
	}

	private boolean isRateLimited(Player player) {
		long now = System.nanoTime();
		Long last = this.lastRequest.get(player.getUniqueId());
		if (last != null && now - last < this.minRequestIntervalNanos) return true;

		// Only an answered request restarts the wait, so a client that keeps asking is still answered at the limit
		this.lastRequest.put(player.getUniqueId(), now);
		return false;
	}

	protected void sendData(Player player, CompoundTag tag) {
		trim(tag);
		FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
		try {
			ByteBufCodecs.COMPOUND_TAG.encode(out, tag);
			this.send(player, RECEIVE_DATA, out);
		} finally {
			out.release();
		}
	}

	protected void send(Player player, String channel, FriendlyByteBuf buf) {
		byte[] bytes = new byte[buf.readableBytes()];
		buf.readBytes(bytes);
		player.sendPluginMessage(this.plugin, channel, bytes);
	}

	/**
	 * Drops the largest entries until the reply fits, like Jade does (x/y/z/ids are tiny and survive).
	 */
	private static void trim(CompoundTag tag) {
		for (int attempts = 0; attempts < 10 && tag.sizeInBytes() > MAX_REPLY_BYTES; attempts++) {
			String largestKey = null;
			int largestSize = 0;
			for (String key : Nms.keys(tag)) {
				Tag child = tag.get(key);
				int size = child == null ? 0 : child.sizeInBytes();
				if (size > largestSize) {
					largestSize = size;
					largestKey = key;
				}
			}
			if (largestKey == null) return;
			tag.remove(largestKey);
		}
	}
}
