package me.ronaldi2001.modutils.nms.compat.jade;

import io.netty.buffer.Unpooled;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.nms.Nms;
import me.ronaldi2001.modutils.nms.VersionShim;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jade on a server before 1.21.2 (1.20.5 to 1.21.1). Clients on another version (through ViaVersion) speak to it like to any newer server,
 * which is all {@link JadeCompat} does. This adds Jade 15, the Jade of 1.21 and 1.21.1 clients, which is a different
 * conversation: the client sends no handshake. The server pings it with {@code jade:server_ping_v1} when it joins (a
 * config string, the shearable blocks and the data provider ids), and the client then asks for data with
 * {@code jade:request_block} and {@code jade:request_entity}. A request names the target and carries no data of the
 * client's (the newer Jade sends a compound tag along), and the answer is a {@code jade:receive_data} compound tag as
 * before. The providers' own layouts are shared, apart from the animal owner (plain text here, see
 * {@link EntityCtx#beforeJade7()}).
 * <p>
 * The two are told apart by the channel a client registers: only Jade 15 listens on the ping.
 */
public class Jade121Compat extends JadeCompat {
	/** The protocol of 1.21.2, the first with the world border flag in a block hit. */
	private static final int PROTOCOL_BORDER_FLAG = 768;
	private static final String SERVER_PING = "jade:server_ping_v1";
	/**
	 * How long after joining the client's channels are checked for, in case it registered them in a way that fired no
	 * register event.
	 */
	private static final long JOIN_CHECK_DELAY_TICKS = 40L;

	/**
	 * Players that were sent the ping on this connection.
	 */
	private final Set<UUID> pinged = ConcurrentHashMap.newKeySet();

	public Jade121Compat(ModUtilsPlugin plugin) {
		super(plugin);
	}

	@Override
	protected String[] presenceChannels() {
		return this.withPings(super.presenceChannels());
	}

	@Override
	protected String[] outgoingChannels() {
		return this.withPings(super.outgoingChannels());
	}

	private String[] withPings(String[] newer) {
		String[] pings = this.pingChannels();
		String[] all = Arrays.copyOf(newer, newer.length + pings.length);
		System.arraycopy(pings, 0, all, newer.length, pings.length);
		return all;
	}

	@Override
	public void onEnable(ConfigurationSection section) {
		super.onEnable(section);
		// Players already online (e.g. after a reload) that have the mod
		this.plugin.getServer().getOnlinePlayers().forEach(this::pingIfReady);
	}

	@Override
	public void onDisable() {
		super.onDisable();
		this.pinged.clear();
	}

	@Override
	protected void forget(UUID id) {
		this.pinged.remove(id);
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		this.pinged.remove(player.getUniqueId());
		this.plugin.getScheduler().runOnEntityDelayed(player, () -> {
			if (!this.plugin.getBedrockHook().isBedrock(player)) this.pingIfReady(player);
		}, JOIN_CHECK_DELAY_TICKS);
	}

	/**
	 * The channels the older Jades are pinged on, by the channel they register.
	 */
	protected String[] pingChannels() {
		return new String[]{SERVER_PING};
	}

	private void pingIfReady(Player player) {
		for (String channel : this.pingChannels()) {
			if (player.getListeningPluginChannels().contains(channel)) {
				this.clientReady(player, ((CraftPlayer) player).getHandle(), channel);
			}
		}
	}

	/**
	 * A client on 1.21.2 or newer writes the block hit one flag longer than a server before that reads it.
	 */
	@Override
	protected boolean hitHasBorderFlag(Player player) {
		me.ronaldi2001.modutils.pluginHooks.via.ViaVersionHook via = this.plugin.getViaVersionHook();
		return via != null && SharedConstants.getProtocolVersion() < PROTOCOL_BORDER_FLAG
				&& via.getPlayerVersion(player) >= PROTOCOL_BORDER_FLAG;
	}

	private boolean isJade15(Player player) {
		return player.getListeningPluginChannels().contains(SERVER_PING);
	}

	@Override
	protected void handle(String channel, Player player, ServerPlayer handle, RegistryFriendlyByteBuf in) {
		if (!this.isJade15(player)) {
			super.handle(channel, player, handle, in);
			return;
		}
		switch (channel) {
			case REQUEST_BLOCK -> this.handleBlockRequest(player, handle, in);
			case REQUEST_ENTITY -> this.handleEntityRequest(player, handle, in);
			default -> {
			}
		}
	}

	/**
	 * What Jade sends a client when it joins: the server's config (none), the blocks shears work on (none), and the ids
	 * of the providers the client can ask. Requests then name providers by their place in these lists.
	 */
	@Override
	@SuppressWarnings("resource") // ServerLevel is closeable, but it belongs to the server
	protected void clientReady(Player player, ServerPlayer handle, String channel) {
		if (!SERVER_PING.equals(channel)) {
			super.clientReady(player, handle, channel);
			return;
		}
		if (!this.firstPing(player)) return;

		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), Nms.level(handle).registryAccess());
		try {
			ByteBufCodecs.STRING_UTF8.encode(out, ""); // server config
			out.writeVarInt(0); // shearable blocks
			this.writeProviderIds(out);
			this.send(player, SERVER_PING, out);
			this.debug("Sent the ping to " + player.getName());
		} finally {
			out.release();
		}
	}

	/**
	 * @return whether this is the first ping for this connection (a client is only pinged once)
	 */
	protected boolean firstPing(Player player) {
		return this.pinged.add(player.getUniqueId());
	}

	private void handleBlockRequest(Player player, ServerPlayer handle, RegistryFriendlyByteBuf in) {
		boolean showDetails = in.readBoolean();
		BlockHitResult hit = VersionShim.readBlockHitResult(in);
		in.readVarInt(); // the block state the client sees, unused
		ItemStack.OPTIONAL_STREAM_CODEC.decode(in); // the item of a fake block, unused
		List<JadeBlockProvider> requested = this.readProviders(in, this.blockProviders);
		BlockPos pos = hit.getBlockPos();
		this.processBlock(player, handle, pos, showDetails, new CompoundTag(), true, this.runBlockProviders(requested));
	}

	private void handleEntityRequest(Player player, ServerPlayer handle, RegistryFriendlyByteBuf in) {
		boolean showDetails = in.readBoolean();
		int entityId = in.readVarInt();
		in.readVarInt(); // part index (multipart entities), unused
		in.readFloat();
		in.readFloat();
		in.readFloat(); // hit vector, unused
		List<JadeEntityProvider> requested = this.readProviders(in, this.entityProviders);
		this.processEntity(player, handle, entityId, showDetails, new CompoundTag(), true, this.runEntityProviders(requested));
	}
}
