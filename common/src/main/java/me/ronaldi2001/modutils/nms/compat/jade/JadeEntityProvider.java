package me.ronaldi2001.modutils.nms.compat.jade;

import net.minecraft.resources.Identifier;

public interface JadeEntityProvider {
	/** Must match the id of the Jade client provider this feeds. */
	Identifier id();

	/** Key under {@code modCompat.jade.features} that toggles this provider. */
	String configKey();

	void append(EntityCtx ctx);
}
