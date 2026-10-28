package me.ronaldi2001.modutils.nms;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Finds the Netty channel behind a player or a connection in its configuration phase, to put handlers in its pipeline.
 */
@SuppressWarnings("unused") // the shared code that calls this is only compiled into the merged adapter
public final class Connections {
	private Connections() {
	}

	/** The channel of a player who has joined, or null if it can't be found. */
	public static @Nullable Channel channelOf(Player player) throws ReflectiveOperationException {
		return channelOf(((CraftPlayer) player).getHandle().connection);
	}

	/**
	 * Finds the Netty channel behind the connection object Paper hands to plugins in the configuration phase.
	 * <p>
	 * Paper's API connection wraps the server's packet listener, which owns the {@link Connection}, but none of those
	 * are exposed, so they are looked up by type rather than by field name.
	 */
	public static @Nullable Channel channelOf(Object apiConnection) throws ReflectiveOperationException {
		ServerCommonPacketListenerImpl listener = findListener(apiConnection, 0);
		if (listener == null) return null;

		Connection connection = fieldOfType(ServerCommonPacketListenerImpl.class, Connection.class, listener);
		return fieldOfType(Connection.class, Channel.class, connection);
	}

	/** By type, not by name: a server that keeps its own (obfuscated) names has other field names. */
	private static <T> @Nullable T fieldOfType(Class<?> owner, Class<T> type, Object instance) throws ReflectiveOperationException {
		for (Field field : owner.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) || !type.isAssignableFrom(field.getType())) continue;
			field.setAccessible(true);
			return type.cast(field.get(instance));
		}
		return null;
	}

	private static @Nullable ServerCommonPacketListenerImpl findListener(@Nullable Object object, int depth) {
		if (object == null || depth > 3) return null;
		if (object instanceof ServerCommonPacketListenerImpl listener) return listener;
		if (object.getClass().getName().startsWith("java.")) return null;

		for (Class<?> type = object.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
				try {
					field.setAccessible(true);
					ServerCommonPacketListenerImpl found = findListener(field.get(object), depth + 1);
					if (found != null) return found;
				} catch (Exception ignored) {
					// Not readable, move on
				}
			}
		}
		return null;
	}
}
