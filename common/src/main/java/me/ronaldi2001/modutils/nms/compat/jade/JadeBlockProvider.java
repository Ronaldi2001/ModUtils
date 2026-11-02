package me.ronaldi2001.modutils.nms.compat.jade;

import net.minecraft.resources.Identifier;

public interface JadeBlockProvider {
	/** Must match the id of the Jade client provider this feeds. */
	Identifier id();

	/** Key under {@code modCompat.jade.features} that toggles this provider. */
	String configKey();

	/**
	 * True to answer every block request, not only the ones where the client asked for this provider. For data the
	 * client can't know it needs, such as the name of a custom block.
	 */
	default boolean alwaysRun() {
		return false;
	}

	/**
	 * True to also answer unasked for the block a player looks at when it has no block entity, which Jade never asks
	 * about. For a plugin's custom block that has a name or contents (see JadeCompat's push).
	 */
	default boolean pushedWithoutBlockEntity() {
		return false;
	}

	void append(BlockCtx ctx);
}
