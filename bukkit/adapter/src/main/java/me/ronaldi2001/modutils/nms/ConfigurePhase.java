package me.ronaldi2001.modutils.nms;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerConnectionListener;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Gets a compat to see every connection while it is still in its configuration phase, before the player exists.
 * <p>
 * Spigot has no event for that (Paper has), so it is made here: a handler on the server's listening channels puts a probe
 * in the pipeline of every connection that is accepted. The probe waits for the first packet the server sends once the
 * connection is configuring, and tells the handlers then. It is the same moment Paper's event comes at.
 * <p>
 * The Paper adapters have a file with the same path (paper/adapter) that uses Paper's event instead.
 */
public final class ConfigurePhase {
	private static final String ACCEPTOR_NAME = "modutils_acceptor";
	private static final String PROBE_NAME = "modutils_configure_probe";

	private static final List<Handler> HANDLERS = new CopyOnWriteArrayList<>();
	private static final List<Channel> LISTENING = new ArrayList<>();
	private static volatile boolean injected;

	private ConfigurePhase() {
	}

	public interface Handler {
		/**
		 * @param id         the player's UUID
		 * @param connection the server's packet listener of the connection, for {@code Connections.channelOf}
		 */
		void onConfigure(UUID id, String name, Object connection);
	}

	/**
	 * @return whether connections are seen in their configuration phase: false until a handler is registered and the
	 * listening channels were found
	 */
	public static boolean available() {
		return injected;
	}

	/**
	 * @return an action that stops the handler from being called again
	 */
	public static synchronized Runnable register(ModUtilsPlugin plugin, Handler handler) {
		HANDLERS.add(handler);
		if (!injected) inject(plugin);
		return () -> remove(handler);
	}

	private static synchronized void remove(Handler handler) {
		HANDLERS.remove(handler);
		if (HANDLERS.isEmpty()) uninject();
	}

	private static void inject(ModUtilsPlugin plugin) {
		try {
			ServerConnectionListener listener = MinecraftServer.getServer().getConnection();
			if (listener == null) throw new IllegalStateException("the server is not listening yet");

			// By type: Spigot keeps its own (obfuscated) field names before 26.1
			for (Field field : ServerConnectionListener.class.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) continue;
				field.setAccessible(true);
				if (!(field.get(listener) instanceof List<?> list)) continue;
				for (Object element : new ArrayList<>(list)) {
					if (element instanceof ChannelFuture future) LISTENING.add(future.channel());
				}
			}
			if (LISTENING.isEmpty()) throw new IllegalStateException("no listening channel found");

			for (Channel channel : LISTENING) channel.pipeline().addFirst(ACCEPTOR_NAME, new Acceptor());
			injected = true;
		} catch (Throwable e) {
			LISTENING.clear();
			plugin.getLogger().log(Level.WARNING, "Could not watch connections in their configuration phase; the mod compats "
					+ "that need it (Polymer, JEI with custom items) fall back to the join event", e);
		}
	}

	private static void uninject() {
		for (Channel channel : LISTENING) {
			channel.eventLoop().execute(() -> {
				if (channel.pipeline().get(ACCEPTOR_NAME) != null) channel.pipeline().remove(ACCEPTOR_NAME);
			});
		}
		LISTENING.clear();
		injected = false;
	}

	/** On the listening channel: sees every connection the server accepts, before the server's own handlers are on it. */
	private static final class Acceptor extends ChannelInboundHandlerAdapter {
		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			if (msg instanceof Channel child) {
				child.pipeline().addLast(new ChannelInitializer<Channel>() {
					@Override
					protected void initChannel(Channel channel) {
						channel.pipeline().addLast(new Installer());
					}
				});
			}
			ctx.fireChannelRead(msg);
		}
	}

	/** Waits until the server has put its handlers on the connection, then adds the probe behind its encoder. */
	private static final class Installer extends ChannelInboundHandlerAdapter {
		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			if (ctx.pipeline().get("encoder") != null && ctx.pipeline().get(PROBE_NAME) == null) {
				ctx.pipeline().addAfter("encoder", PROBE_NAME, new Probe());
			}
			ctx.pipeline().remove(this);
			super.channelActive(ctx);
		}
	}

	/** Sees the packets the server sends, as objects. The first one in the configuration phase is the signal. */
	private static final class Probe extends ChannelOutboundHandlerAdapter {
		private boolean done;

		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
			if (!this.done && ctx.pipeline().get("packet_handler") instanceof Connection connection) {
				PacketListener listener = connection.getPacketListener();
				if (listener instanceof ServerConfigurationPacketListenerImpl configuration) {
					this.done = true;
					ctx.channel().eventLoop().execute(() -> ctx.pipeline().remove(this));
					signal(configuration);
				}
			}
			ctx.write(msg, promise);
		}

		private static void signal(ServerConfigurationPacketListenerImpl configuration) {
			var profile = configuration.getOwner();
			UUID id = VersionProfile.id(profile);
			if (id == null) return;
			String name = VersionProfile.name(profile);
			for (Handler handler : HANDLERS) {
				try {
					handler.onConfigure(id, name, configuration);
				} catch (Throwable e) {
					// One handler failing must not keep the connection from being set up
					java.util.logging.Logger.getLogger("ModUtils").log(Level.WARNING, "A configuration handler failed", e);
				}
			}
		}
	}
}
