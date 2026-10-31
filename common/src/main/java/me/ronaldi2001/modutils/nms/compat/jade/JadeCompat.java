package me.ronaldi2001.modutils.nms.compat.jade;

import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.nms.Nms;
import me.ronaldi2001.modutils.nms.VersionShim;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import me.ronaldi2001.modutils.platform.ScheduledTask;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jade 1.21.2 and newer. The client sends {@code jade:client_handshake}; we reply with {@code jade:server_handshake}
 * listing our provider ids. Requests carry indices into that list and a compound tag of the client's, and we answer every
 * request with a {@code jade:receive_data} compound tag.
 * <p>
 * Protocol versions 7 to 9, see {@link #PROTOCOL_VERSIONS}.
 */
public class JadeCompat extends JadeCompatBase {
	/**
	 * The protocol strings of the Jade versions we answer: 7 is Jade for 1.21.2 to 1.21.5, 8 is 1.21.6 to 1.21.8, and 9
	 * is 1.21.9 and newer. The packets are laid out the same in all of them; only the string changed.
	 */
	private static final Set<String> PROTOCOL_VERSIONS = Set.of("7", "8", "9");

	protected static final String CLIENT_HANDSHAKE = "jade:client_handshake";
	protected static final String SERVER_HANDSHAKE = "jade:server_handshake";
	protected static final String REQUEST_BLOCK = "jade:request_block";
	protected static final String REQUEST_ENTITY = "jade:request_entity";

	/** How long a pushed name is repeated for the block still looked at, in ticks, in case the client dropped it. */
	private static final long PUSH_REPEAT_TICKS = 20;
	private static final Identifier OBJECT_NAME = Identifier.fromNamespaceAndPath("jade", "object_name");

	private final Map<UUID, ScheduledTask> pushTasks = new ConcurrentHashMap<>();
	private boolean pushBlockNames;
	/** How often the block a player looks at is checked, in ticks. */
	private long pushPeriodTicks = 4;

	public JadeCompat(ModUtilsPlugin plugin) {
		super(plugin);
	}

	@Override
	public void onEnable(ConfigurationSection section) {
		super.onEnable(section);
		this.pushBlockNames = section.getBoolean("pushCustomBlockNames", true);
		this.pushPeriodTicks = Math.max(1, section.getLong("pushIntervalTicks", 4L));
	}

	@Override
	public void onDisable() {
		super.onDisable();
		this.pushTasks.values().forEach(ScheduledTask::cancel);
		this.pushTasks.clear();
	}

	@Override
	protected void forget(UUID id) {
		ScheduledTask task = this.pushTasks.remove(id);
		if (task != null) task.cancel();
	}

	@Override
	protected String[] presenceChannels() {
		return new String[]{SERVER_HANDSHAKE};
	}

	@Override
	protected String[] incomingChannels() {
		return new String[]{CLIENT_HANDSHAKE, REQUEST_BLOCK, REQUEST_ENTITY};
	}

	@Override
	protected String[] outgoingChannels() {
		return new String[]{SERVER_HANDSHAKE, RECEIVE_DATA};
	}

	@Override
	protected void handle(String channel, Player player, ServerPlayer handle, RegistryFriendlyByteBuf in) {
		switch (channel) {
			case CLIENT_HANDSHAKE -> this.handleHandshake(player, handle, in);
			case REQUEST_BLOCK -> this.handleBlockRequest(player, handle, in);
			case REQUEST_ENTITY -> this.handleEntityRequest(player, handle, in);
			default -> {
			}
		}
	}

	@Override
	protected void clientReady(Player player, ServerPlayer handle, String channel) {
		this.debug("Sending the handshake to " + player.getName());
		this.sendHandshake(player, handle);
		this.startNamePush(player, handle);
	}

	/**
	 * Jade only asks the server about a block that has a block entity, so a plugin's custom block (a vanilla block to the
	 * server) never gets its name looked up. The client takes {@code jade:receive_data} for the block it is looking at
	 * whether it asked or not, as long as the position in it is that block's. So look at what the player looks at, and
	 * send the name unasked.
	 */
	private void startNamePush(Player player, ServerPlayer handle) {
		if (!this.pushBlockNames || this.pushTasks.containsKey(player.getUniqueId())) return;

		JadeBlockProvider names = this.blockProviders.stream().filter(p -> OBJECT_NAME.equals(p.id())).findFirst().orElse(null);
		if (names == null) return;

		BlockPos[] last = {null};
		long[] sentAt = {0};
		ScheduledTask task = this.plugin.getScheduler().runOnEntityTimer(player, () -> {
			if (!this.detect(player)) return;

			Block target = player.getTargetBlockExact(Math.max(1, (int) Math.ceil(handle.blockInteractionRange())));
			if (target == null) {
				last[0] = null;
				return;
			}

			BlockPos pos = new BlockPos(target.getX(), target.getY(), target.getZ());
			long now = System.currentTimeMillis();
			if (pos.equals(last[0]) && now - sentAt[0] < PUSH_REPEAT_TICKS * 50) return;
			last[0] = pos;
			sentAt[0] = now;

			ServerLevel level = Nms.level(handle);
			BlockState state = level.getBlockState(pos);
			// Jade asks about these itself
			if (state.hasBlockEntity()) return;

			CompoundTag tag = new CompoundTag();
			tag.putInt("x", pos.getX());
			tag.putInt("y", pos.getY());
			tag.putInt("z", pos.getZ());
			tag.putString("BlockId", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
			names.append(new BlockCtx(handle, level, pos, state, null, false, tag, null));
			if (!tag.contains(OBJECT_NAME.toString())) return;

			this.debug("Pushing the name of the block at " + pos.toShortString() + " to " + player.getName());
			this.sendData(player, tag);
		}, () -> this.pushTasks.remove(player.getUniqueId()), 20, this.pushPeriodTicks);
		if (task != null) this.pushTasks.put(player.getUniqueId(), task);
	}

	/**
	 * Whether this client writes a block hit with the flag for hitting the world border after it, which Minecraft added
	 * in 1.21.2. The server reads its own version's layout, so only a server before that can be sent one it cannot read.
	 */
	protected boolean hitHasBorderFlag(Player player) {
		return false;
	}

	private void handleHandshake(Player player, ServerPlayer handle, RegistryFriendlyByteBuf in) {
		String version = ByteBufCodecs.STRING_UTF8.decode(in);
		if (!PROTOCOL_VERSIONS.contains(version)) {
			this.plugin.getLogger().fine("Jade protocol mismatch from " + player.getName() + ": " + version);
			return;
		}
		this.sendHandshake(player, handle);
	}

	@SuppressWarnings("resource") // ServerLevel is closeable, but it belongs to the server
	private void sendHandshake(Player player, ServerPlayer handle) {
		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), Nms.level(handle).registryAccess());
		try {
			out.writeVarInt(0); // server config map (nothing to sync)
			out.writeVarInt(0); // shearable blocks
			this.writeProviderIds(out);
			this.send(player, SERVER_HANDSHAKE, out);
		} finally {
			out.release();
		}
	}

	private void handleBlockRequest(Player player, ServerPlayer handle, RegistryFriendlyByteBuf in) {
		boolean showDetails = in.readBoolean();
		BlockHitResult hit = VersionShim.readBlockHitResult(in);
		if (this.hitHasBorderFlag(player)) in.readBoolean(); // whether the hit was on the world border
		ItemStack.OPTIONAL_STREAM_CODEC.decode(in); // serverside representation, unused
		CompoundTag tag = ByteBufCodecs.COMPOUND_TAG.decode(in);
		List<JadeBlockProvider> requested = this.readProviders(in, this.blockProviders);
		BlockPos pos = hit.getBlockPos();
		this.processBlock(player, handle, pos, showDetails, tag, false, this.runBlockProviders(requested));
	}

	private void handleEntityRequest(Player player, ServerPlayer handle, RegistryFriendlyByteBuf in) {
		boolean showDetails = in.readBoolean();
		int entityId = in.readVarInt();
		in.readVarInt(); // part index (multipart entities), unused
		in.readFloat();
		in.readFloat();
		in.readFloat(); // hit vector, unused
		CompoundTag tag = ByteBufCodecs.COMPOUND_TAG.decode(in);
		List<JadeEntityProvider> requested = this.readProviders(in, this.entityProviders);
		this.processEntity(player, handle, entityId, showDetails, tag, false, this.runEntityProviders(requested));
	}
}
