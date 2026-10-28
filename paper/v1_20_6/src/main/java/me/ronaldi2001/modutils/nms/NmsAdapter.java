package me.ronaldi2001.modutils.nms;

import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.adapter.VersionAdapter;
import me.ronaldi2001.modutils.modBlock.ModBlockBridge;
import me.ronaldi2001.modutils.modCompat.api.ModCompat;
import me.ronaldi2001.modutils.nms.compat.appleSkin.AppleSkinCompat;
import me.ronaldi2001.modutils.nms.modBlock.NmsModBlockBridge;
import me.ronaldi2001.modutils.nms.compat.jade.Jade1205Compat;

import me.ronaldi2001.modutils.nms.compat.top.TopCompat;

import java.util.List;

/**
 * Creates the compats for 1.20.5 and 1.20.6: AppleSkin, Jade and TheOneProbe. JEI and Polymer are not here: they work from 1.21.3 on,
 * so a 1.20.6 server does not list them at all. Jade also answers the Jade of 1.21 and newer clients, see
 * {@link Jade1205Compat}.
 */
public final class NmsAdapter implements VersionAdapter {
	@Override
	public List<ModCompat> createCompats(ModUtilsPlugin plugin) {
		return List.of(
				new AppleSkinCompat(plugin),
				new Jade1205Compat(plugin),
				new TopCompat(plugin)
		);
	}

	@Override
	public ModBlockBridge createModBlockBridge(ModUtilsPlugin plugin) {
		return new NmsModBlockBridge(plugin);
	}
}
