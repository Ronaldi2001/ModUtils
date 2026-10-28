package me.ronaldi2001.modutils.modBlock;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * What mod block needs from the server's connections, written by each version adapter against that version's
 * internals: it sees what a client sends, and shows it the fake sign.
 */
public interface ModBlockBridge {
	/** Receives what the clients send. Called on the connection's network thread, so it must be quick and thread-safe. */
	interface Listener {
		/** A client sent a plugin message ({@code minecraft:register} and the like included), in any phase. */
		void onChannel(UUID id, String channel, byte[] data);

		/**
		 * A client sent the text of a sign it edited.
		 *
		 * @return true if it was the fake sign, and the server must not see it
		 */
		boolean onSignUpdate(UUID id, int x, int y, int z, String[] lines);
	}

	/** Starts watching the connections: every player who joins from now on, and everyone online now. */
	void start(Listener listener);

	/** Stops watching and leaves every connection as it was. */
	void stop();

	/**
	 * Watches this player's connection too, if it is not already. For servers that don't tell plugins about connections
	 * until the player has joined.
	 */
	void attach(Player player);

	/**
	 * Shows the player a fake sign at the position and opens its editor. Each line is a translatable component for the
	 * key, with {@code fallback} as the text when the client has no such key.
	 */
	void openProbe(Player player, int x, int y, int z, List<String> keys, String fallback);

	/** Closes whatever screen the player has open, which makes a sign editor send its text. */
	void closeScreen(Player player);
}
