package me.ronaldi2001.modutils.nms;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The few Minecraft calls whose signature differs between versions. Changed in 26.3; the shared copy covers older
 * versions. An adapter whose version needs something else has its own copy of this file (same path, in its module's
 * src/main/java).
 */
@SuppressWarnings("unused") // the shared code that calls this is only compiled into the merged adapter
public final class VersionShim {
	private VersionShim() {
	}

	public static BlockHitResult readBlockHitResult(RegistryFriendlyByteBuf in) {
		return BlockHitResult.STREAM_CODEC.decode(in);
	}

	/** Drops the stack at the player's feet, as if the player threw it. */
	public static void dropItem(Player player, ItemStack stack) {
		player.drop(stack, false, Prediction.SERVER_ONLY);
	}

	/** The packet that opens the editor of the front of a sign. */
	public static Packet<?> openSignEditor(BlockPos pos) {
		return new ClientboundOpenSignEditorPacket(pos, SignTextSlot.FRONT);
	}

	public static BlockPos signPos(ServerboundSignUpdatePacket packet) {
		return packet.pos();
	}

	public static String[] signLines(ServerboundSignUpdatePacket packet) {
		return packet.lines().toArray(new String[0]);
	}
}
