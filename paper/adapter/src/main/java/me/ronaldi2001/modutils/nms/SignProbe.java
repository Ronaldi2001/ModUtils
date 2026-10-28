package me.ronaldi2001.modutils.nms;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Shows a player a sign with the given translatable lines, which only exists on their client. Paper writes the sign data
 * itself, in the form this version wants. The Bukkit adapters have a file with the same path (bukkit/adapter).
 */
public final class SignProbe {
	private SignProbe() {
	}

	public static void draw(Player player, int x, int y, int z, List<String> keys, String fallback) {
		Location location = new Location(player.getWorld(), x, y, z);
		BlockData data = Bukkit.createBlockData(Material.OAK_SIGN);
		Sign sign = (Sign) data.createBlockState();
		SignSide front = sign.getSide(Side.FRONT);
		for (int i = 0; i < 4; i++) {
			front.line(i, i < keys.size() ? Component.translatable(keys.get(i), fallback) : Component.empty());
		}

		player.sendBlockChange(location, data);
		player.sendBlockUpdate(location, sign);
	}
}
