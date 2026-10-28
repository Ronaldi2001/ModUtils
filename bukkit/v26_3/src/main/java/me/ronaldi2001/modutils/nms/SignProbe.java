package me.ronaldi2001.modutils.nms;

import com.mojang.serialization.DataResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.SignText;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows a player a sign with the given translatable lines, which only exists on their client. Spigot has no way to give a
 * sign a translatable line (and no {@code sendBlockUpdate}), so the packets are built from the server's own sign text.
 * The Paper adapters have a file with the same path (paper/adapter) that uses Paper's API. This is the form of 26.3, where
 * SignText changed; the other versions use bukkit/adapter.
 */
public final class SignProbe {
	private SignProbe() {
	}

	public static void draw(Player player, int x, int y, int z, List<String> keys, String fallback) {
		BlockPos pos = new BlockPos(x, y, z);
		// 26.3 builds a sign text from its lines instead of changing one line at a time
		List<Component> lines = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			lines.add(i < keys.size() ? Component.translatableWithFallback(keys.get(i), fallback) : Component.empty());
		}
		SignText front = new SignText(lines, lines, DyeColor.BLACK, false);

		// The sign data as the client reads it: the text in this version's own form, written by the server's codec
		DataResult<Tag> encoded = SignText.CODEC.encodeStart(NbtOps.INSTANCE, front);
		CompoundTag data = new CompoundTag();
		data.put("front_text", encoded.result().orElseThrow());
		data.put("back_text", SignText.CODEC.encodeStart(NbtOps.INSTANCE, SignText.EMPTY).result().orElseThrow());
		data.putBoolean("is_waxed", false);

		var state = Blocks.OAK_SIGN.defaultBlockState();
		var connection = ((CraftPlayer) player).getHandle().connection;
		connection.send(new ClientboundBlockUpdatePacket(pos, state));
		connection.send(ClientboundBlockEntityDataPacket.create(new SignBlockEntity(pos, state), (entity, registries) -> data));
	}
}
