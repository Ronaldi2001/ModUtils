package me.ronaldi2001.modutils.nms;

import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.adapter.VersionAdapter;
import me.ronaldi2001.modutils.modBlock.ModBlockBridge;
import me.ronaldi2001.modutils.modCompat.api.ModCompat;
import me.ronaldi2001.modutils.nms.compat.appleSkin.AppleSkinCompat;
import me.ronaldi2001.modutils.nms.modBlock.NmsModBlockBridge;
import me.ronaldi2001.modutils.nms.compat.jade.Jade121Compat;

import me.ronaldi2001.modutils.nms.compat.top.TopCompat;

import java.util.List;

/**
 * Creates the compats for 1.21 and 1.21.1: AppleSkin, Jade and TheOneProbe. JEI and Polymer are not here: they work from 1.21.3 on
 * (the first version after this one with a Paper server), so a 1.21.1 server does not list them at all. Jade also
 * answers the Jade of 1.21 and 1.21.1 clients, see {@link Jade121Compat}.
 */
public final class NmsAdapter implements VersionAdapter {
	@Override
	public List<ModCompat> createCompats(ModUtilsPlugin plugin) {
		return List.of(
				new AppleSkinCompat(plugin),
				new Jade121Compat(plugin),
				new TopCompat(plugin)
		);
	}

	@Override
	public ModBlockBridge createModBlockBridge(ModUtilsPlugin plugin) {
		return new NmsModBlockBridge(plugin);
	}
}
