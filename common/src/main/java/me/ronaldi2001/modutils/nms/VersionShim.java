package me.ronaldi2001.modutils.nms;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The few Minecraft calls whose signature differs between versions. This is the version used by 1.21.11 to 26.2; an
 * adapter whose version needs something else has its own copy of this file (same path, in its module's src/main/java).
 */
public final class VersionShim {
	private VersionShim() {
	}

	public static BlockHitResult readBlockHitResult(RegistryFriendlyByteBuf in) {
		return in.readBlockHitResult();
	}

	/** Drops the stack at the player's feet, as if the player threw it. */
	public static void dropItem(Player player, ItemStack stack) {
		player.drop(stack, false);
	}

	/** The packet that opens the editor of the front of a sign. */
	public static Packet<?> openSignEditor(BlockPos pos) {
		return new ClientboundOpenSignEditorPacket(pos, true);
	}

	public static BlockPos signPos(ServerboundSignUpdatePacket packet) {
		return packet.getPos();
	}

	public static String[] signLines(ServerboundSignUpdatePacket packet) {
		return packet.getLines();
	}
}
