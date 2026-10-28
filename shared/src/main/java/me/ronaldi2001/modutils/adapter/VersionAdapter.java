package me.ronaldi2001.modutils.adapter;

import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.modBlock.ModBlockBridge;
import me.ronaldi2001.modutils.modCompat.api.ModCompat;

import java.util.List;

/**
 * The entry point of a version adapter. Each adapter is compiled against one Minecraft version and does everything that
 * needs that version's internals. The loader finds it as {@code me.ronaldi2001.modutils.nms.<adapter id>.NmsAdapter}.
 */
public interface VersionAdapter {
	/** The compats this Minecraft version has. */
	List<ModCompat> createCompats(ModUtilsPlugin plugin);

	/** The connection access that mod block needs. */
	ModBlockBridge createModBlockBridge(ModUtilsPlugin plugin);
}
