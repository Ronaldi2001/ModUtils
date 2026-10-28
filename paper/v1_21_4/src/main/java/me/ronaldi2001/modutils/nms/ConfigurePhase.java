package me.ronaldi2001.modutils.nms;

import me.ronaldi2001.modutils.ModUtilsPlugin;

import java.util.UUID;

/**
 * This Paper version does not tell plugins about connections in their configuration phase, so
 * nothing is ever called back. The compats that need it go by the join event instead.
 */
@SuppressWarnings("unused") // the shared code that calls this is only compiled into the merged adapter
public final class ConfigurePhase {
	private ConfigurePhase() {
	}

	public interface Handler {
		void onConfigure(UUID id, String name, Object connection);
	}

	public static boolean available() {
		return false;
	}

	public static Runnable register(ModUtilsPlugin plugin, Handler handler) {
		return () -> {
		};
	}
}
