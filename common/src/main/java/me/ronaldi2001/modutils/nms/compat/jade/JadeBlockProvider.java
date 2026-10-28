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

	void append(BlockCtx ctx);
}
