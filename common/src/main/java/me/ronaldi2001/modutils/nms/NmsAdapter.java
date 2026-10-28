package me.ronaldi2001.modutils.nms;

import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.adapter.VersionAdapter;
import me.ronaldi2001.modutils.modBlock.ModBlockBridge;
import me.ronaldi2001.modutils.modCompat.api.ModCompat;
import me.ronaldi2001.modutils.nms.compat.appleSkin.AppleSkinCompat;
import me.ronaldi2001.modutils.nms.modBlock.NmsModBlockBridge;
import me.ronaldi2001.modutils.nms.compat.jade.JadeCompat;
import me.ronaldi2001.modutils.nms.compat.jei.JeiCompat;
import me.ronaldi2001.modutils.nms.compat.polymer.PolymerCompat;
import me.ronaldi2001.modutils.nms.compat.top.TopCompat;

import java.util.List;

/**
 * Creates the compats for the Minecraft version this adapter was compiled against. Add a new mod by creating a
 * {@link ModCompat} and listing it here.
 */
public final class NmsAdapter implements VersionAdapter {
	@Override
	public List<ModCompat> createCompats(ModUtilsPlugin plugin) {
		return List.of(
				new PolymerCompat(plugin),
				new AppleSkinCompat(plugin),
				new JadeCompat(plugin),
				new TopCompat(plugin),
				new JeiCompat(plugin)
		);
	}

	@Override
	public ModBlockBridge createModBlockBridge(ModUtilsPlugin plugin) {
		return new NmsModBlockBridge(plugin);
	}
}
