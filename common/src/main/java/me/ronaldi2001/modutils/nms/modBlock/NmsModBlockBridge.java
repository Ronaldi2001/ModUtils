package me.ronaldi2001.modutils.nms.modBlock;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.modBlock.ModBlockBridge;
import me.ronaldi2001.modutils.nms.ConfigurePhase;
import me.ronaldi2001.modutils.nms.Connections;
import me.ronaldi2001.modutils.nms.Nms;
import me.ronaldi2001.modutils.nms.SignProbe;
import me.ronaldi2001.modutils.nms.VersionShim;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Mod block's access to the connections, for the Minecraft version this adapter is compiled against.
 * <p>
 * A handler in each connection's pipeline sees the packets the client sends, as the server's own objects: plugin
 * messages (in the configuration phase too) and the text of an edited sign. The fake sign is drawn by {@link SignProbe}, which is
 * different on Paper and Bukkit; opening and closing the editor are packets.
 */
public final class NmsModBlockBridge implements ModBlockBridge {
	private static final String HANDLER_NAME = "modutils_modblock";

	private final ModUtilsPlugin plugin;
	private final Map<UUID, Channel> attached = new ConcurrentHashMap<>();
	private volatile @Nullable Listener listener;
	private @Nullable Runnable stopConfigure;

	public NmsModBlockBridge(ModUtilsPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public void start(Listener listener) {
		this.listener = listener;
		this.stopConfigure = ConfigurePhase.register(this.plugin, this::onConfigure);
		for (Player player : this.plugin.getServer().getOnlinePlayers()) this.attach(player);
	}

	@Override
	public void stop() {
		this.listener = null;
		if (this.stopConfigure != null) {
			this.stopConfigure.run();
			this.stopConfigure = null;
		}
		for (Channel channel : this.attached.values()) {
			try {
				if (channel.pipeline().get(HANDLER_NAME) != null) channel.pipeline().remove(HANDLER_NAME);
			} catch (Exception ignored) {
				// The connection is already gone
			}
		}
		this.attached.clear();
	}

	@Override
	public void attach(Player player) {
		if (this.attached.containsKey(player.getUniqueId())) return;

		try {
			this.inject(player.getUniqueId(), Connections.channelOf(player));
		} catch (Exception e) {
			this.plugin.getLogger().log(Level.WARNING, "[ModBlock] Could not watch the connection of " + player.getName(), e);
		}
	}

	/** A connection in its configuration phase, before the player exists: where a client announces its mods first. */
	private void onConfigure(UUID id, String name, Object connection) {
		if (this.plugin.getBedrockHook().isBedrock(id)) return;

		try {
			this.inject(id, Connections.channelOf(connection));
		} catch (Exception e) {
			this.plugin.getLogger().log(Level.WARNING, "[ModBlock] Could not watch the connection of " + name, e);
		}
	}

	private void inject(UUID id, @Nullable Channel channel) {
		if (channel == null || channel.pipeline().get(HANDLER_NAME) != null) return;

		channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new Handler(id));
		this.attached.put(id, channel);
		channel.closeFuture().addListener(future -> this.attached.remove(id, channel));
	}

	@Override
	public void openProbe(Player player, int x, int y, int z, List<String> keys, String fallback) {
		SignProbe.draw(player, x, y, z, keys, fallback);
		((CraftPlayer) player).getHandle().connection.send(VersionShim.openSignEditor(new BlockPos(x, y, z)));
	}

	@Override
	public void closeScreen(Player player) {
		((CraftPlayer) player).getHandle().connection.send(new ClientboundContainerClosePacket(0));
	}

	private final class Handler extends ChannelInboundHandlerAdapter {
		private final UUID id;

		private Handler(UUID id) {
			this.id = id;
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			Listener listener = NmsModBlockBridge.this.listener;
			if (listener != null) {
				try {
					if (msg instanceof ServerboundCustomPayloadPacket packet) {
						CustomPacketPayload payload = packet.payload();
						byte[] data = payload instanceof DiscardedPayload discarded ? Nms.payloadData(discarded) : new byte[0];
						listener.onChannel(this.id, payload.type().id().toString(), data);
					} else if (msg instanceof ServerboundSignUpdatePacket packet) {
						BlockPos pos = VersionShim.signPos(packet);
						// Our fake sign: the server must never see it
						if (listener.onSignUpdate(this.id, pos.getX(), pos.getY(), pos.getZ(), VersionShim.signLines(packet))) return;
					}
				} catch (Exception e) {
					NmsModBlockBridge.this.plugin.getLogger().log(Level.WARNING, "[ModBlock] Could not read a packet", e);
				}
			}
			ctx.fireChannelRead(msg);
		}
	}
}
