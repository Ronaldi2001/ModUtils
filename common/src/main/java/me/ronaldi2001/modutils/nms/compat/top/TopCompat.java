package me.ronaldi2001.modutils.nms.compat.top;

import io.netty.buffer.Unpooled;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.modCompat.api.ModCompat;
import me.ronaldi2001.modutils.nms.ItemWriterCache;
import me.ronaldi2001.modutils.nms.Nms;
import me.ronaldi2001.modutils.nms.compat.jade.BlockCtx;
import me.ronaldi2001.modutils.nms.compat.jade.BlockItemSource;
import me.ronaldi2001.modutils.nms.compat.jade.EntityCtx;
import me.ronaldi2001.modutils.nms.compat.jade.ItemWriter;
import me.ronaldi2001.modutils.nms.compat.jade.providers.ServiceStorageSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * The One Probe (NeoForge, from 1.20.6). TOP's client asks what it is looking at with {@code theoneprobe:getinfo} (a
 * block) or {@code theoneprobe:getentityinfo}, and the server answers with a tree of display elements, see
 * {@link ProbeInfo}. TOP does all of its work on the server, so there is no handshake to answer: a client is known to have
 * the mod by the {@code theoneprobe:returninfo} channel it registers.
 * <p>
 * The packets are the same in every TOP version this plugin supports. Only what is inside them (item stacks and text)
 * depends on the Minecraft version, and that is written by the same code as the other compats.
 */
public class TopCompat extends ModCompat implements PluginMessageListener {
	private static final String GET_INFO = "theoneprobe:getinfo";
	private static final String GET_ENTITY_INFO = "theoneprobe:getentityinfo";
	private static final String RETURN_INFO = "theoneprobe:returninfo";
	private static final String RETURN_ENTITY_INFO = "theoneprobe:returnentityinfo";

	/** {@code ProbeMode.EXTENDED} (the player sneaks); the modes are written as their ordinals. */
	private static final int MODE_EXTENDED = 1;
	private static final double ENTITY_EXTRA_RANGE = 21;

	private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();
	private final ItemWriterCache itemWriters;
	private long minRequestIntervalNanos;
	private boolean debug;
	private ConfigurationSection features;
	private List<BlockItemSource> itemSources = List.of();

	public TopCompat(ModUtilsPlugin plugin) {
		super(plugin);
		this.itemWriters = new ItemWriterCache(plugin);
	}

	@Override
	public String getConfigKey() {
		return "top";
	}

	@Override
	public String getName() {
		return "TheOneProbe";
	}

	@Override
	public boolean detect(Player player) {
		return player.getListeningPluginChannels().contains(RETURN_INFO);
	}

	@Override
	public void onEnable(ConfigurationSection section) {
		this.minRequestIntervalNanos = Math.max(0L, section.getLong("minRequestIntervalMs", 50L)) * 1_000_000L;
		this.debug = section.getBoolean("debug", false);
		this.features = section.getConfigurationSection("features");
		this.itemSources = Stream.of(new ServiceStorageSource())
				.filter(source -> featureOn(source.configKey()))
				.map(source -> (BlockItemSource) source)
				.toList();

		Messenger messenger = this.plugin.getServer().getMessenger();
		messenger.registerIncomingPluginChannel(this.plugin, GET_INFO, this);
		messenger.registerIncomingPluginChannel(this.plugin, GET_ENTITY_INFO, this);
		messenger.registerOutgoingPluginChannel(this.plugin, RETURN_INFO);
		messenger.registerOutgoingPluginChannel(this.plugin, RETURN_ENTITY_INFO);
	}

	@Override
	public void onDisable() {
		Messenger messenger = this.plugin.getServer().getMessenger();
		messenger.unregisterIncomingPluginChannel(this.plugin, GET_INFO, this);
		messenger.unregisterIncomingPluginChannel(this.plugin, GET_ENTITY_INFO, this);
		messenger.unregisterOutgoingPluginChannel(this.plugin, RETURN_INFO);
		messenger.unregisterOutgoingPluginChannel(this.plugin, RETURN_ENTITY_INFO);
		this.lastRequest.clear();
		this.itemWriters.clear();
	}

	@EventHandler
	public void onRegisterChannel(PlayerRegisterChannelEvent event) {
		if (RETURN_INFO.equals(event.getChannel())) this.debug("Client of " + event.getPlayer().getName() + " has TheOneProbe");
	}

	@EventHandler
	public void onUnregisterChannel(PlayerUnregisterChannelEvent event) {
		if (RETURN_INFO.equals(event.getChannel())) this.forget(event.getPlayer().getUniqueId());
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		this.forget(event.getPlayer().getUniqueId());
	}

	private void forget(UUID id) {
		this.lastRequest.remove(id);
		this.itemWriters.remove(id);
	}

	@Override
	@SuppressWarnings("resource") // ServerLevel is closeable, but it belongs to the server
	public void onPluginMessageReceived(@NonNull String channel, @NonNull Player player, byte @NonNull [] message) {
		if (this.plugin.getBedrockHook().isBedrock(player)) return;
		if (!this.detect(player)) return;
		if (this.isRateLimited(player)) return;

		this.debug("Received " + channel + " from " + player.getName() + " (" + message.length + " bytes)");
		try {
			ServerPlayer handle = ((CraftPlayer) player).getHandle();
			RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(message), Nms.level(handle).registryAccess());
			try {
				if (channel.equals(GET_INFO)) {
					this.handleBlock(player, handle, in);
				} else if (channel.equals(GET_ENTITY_INFO)) {
					this.handleEntity(player, handle, in);
				}
			} finally {
				in.release();
			}
		} catch (Exception e) {
			this.plugin.getLogger().log(Level.WARNING, "Failed to handle TheOneProbe packet " + channel + " from " + player.getName(), e);
		}
	}

	/**
	 * Dimension, position and mode; the rest of the request (the side and spot that was hit and the client's own copy of
	 * the block's item) is not needed, and the item is written for the client's version, which may not be ours.
	 */
	@SuppressWarnings("resource") // ServerLevel is closeable, but it belongs to the server
	private void handleBlock(Player player, ServerPlayer handle, RegistryFriendlyByteBuf in) {
		Identifier dimension = Identifier.STREAM_CODEC.decode(in);
		BlockPos pos = in.readBlockPos();
		boolean extended = in.readVarInt() == MODE_EXTENDED;

		Location location = new Location(Nms.level(handle).getWorld(), pos.getX(), pos.getY(), pos.getZ());
		this.plugin.getScheduler().runAtLocation(location, () -> {
			if (!player.isOnline()) return;

			ServerLevel level = Nms.level(handle);
			if (!blockInReach(handle, pos) || !level.isLoaded(pos)) return;

			BlockState state = level.getBlockState(pos);
			if (state.isAir()) return;
			BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;

			ItemWriter items = this.itemWriterFor(player);
			ProbeInfo info = new ProbeInfo();
			try {
				TopProviders.block(new BlockCtx(handle, level, pos, state, blockEntity, extended, new CompoundTag(), items),
						info, this.features, this.itemSources);
			} catch (Exception e) {
				this.plugin.getLogger().log(Level.WARNING, "TheOneProbe block info failed at " + pos, e);
			}

			this.send(player, RETURN_INFO, level, out -> {
				Identifier.STREAM_CODEC.encode(out, dimension);
				out.writeBlockPos(pos);
				info.write(out, items);
			});
		});
	}

	/** Dimension, entity and mode. The dimension is not needed: the entity is looked up in the player's own. */
	@SuppressWarnings("resource") // ServerLevel is closeable, but it belongs to the server
	private void handleEntity(Player player, ServerPlayer handle, RegistryFriendlyByteBuf in) {
		Identifier.STREAM_CODEC.decode(in);
		UUID id = in.readUUID();
		boolean extended = in.readVarInt() == MODE_EXTENDED;

		// The lookup runs where the player is, not on the network thread; the entity itself is then read on its own thread
		this.plugin.getScheduler().runOnEntity(player, () -> {
			Entity target = Nms.level(handle).getEntity(id);
			if (target == null) return;

			this.plugin.getScheduler().runOnEntity(target.getBukkitEntity(), () -> {
				ServerLevel level = Nms.level(handle);
				if (!player.isOnline() || target.level() != level) return;
				if (handle.distanceToSqr(target) > Mth.square(handle.entityInteractionRange() + ENTITY_EXTRA_RANGE)) return;

				ItemWriter items = this.itemWriterFor(player);
				ProbeInfo info = new ProbeInfo();
				try {
					TopProviders.entity(new EntityCtx(handle, level, target, extended, new CompoundTag(), items, false), info, this.features);
				} catch (Exception e) {
					this.plugin.getLogger().log(Level.WARNING, "TheOneProbe entity info failed for " + id, e);
				}

				this.send(player, RETURN_ENTITY_INFO, level, out -> {
					out.writeUUID(id);
					info.write(out, items);
				});
			});
		});
	}

	private boolean featureOn(String key) {
		return this.features == null || this.features.getBoolean(key, true);
	}

	private static boolean blockInReach(ServerPlayer player, BlockPos pos) {
		return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= Mth.square(player.blockInteractionRange() + 3);
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

	private void send(Player player, String channel, ServerLevel level, java.util.function.Consumer<RegistryFriendlyByteBuf> writer) {
		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
		try {
			writer.accept(out);
			byte[] bytes = new byte[out.readableBytes()];
			out.readBytes(bytes);
			player.sendPluginMessage(this.plugin, channel, bytes);
		} finally {
			out.release();
		}
	}

	private void debug(String message) {
		if (this.debug) this.plugin.getLogger().info("[TheOneProbe] " + message);
	}
}
