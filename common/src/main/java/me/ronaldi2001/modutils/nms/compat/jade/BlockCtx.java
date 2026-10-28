package me.ronaldi2001.modutils.nms.compat.jade;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/** Everything a {@link JadeBlockProvider} needs to answer a Jade block request. */
public record BlockCtx(
		ServerPlayer player,
		ServerLevel level,
		BlockPos pos,
		BlockState state,
		@Nullable BlockEntity blockEntity,
		boolean showDetails,
		CompoundTag tag,
		ItemWriter items
) {
	public Block bukkitBlock() {
		return this.level.getWorld().getBlockAt(this.pos.getX(), this.pos.getY(), this.pos.getZ());
	}

	/** Writes stream-encoded data under the provider id, the same way Jade's StreamServerDataProvider does. */
	public void put(Identifier id, Consumer<RegistryFriendlyByteBuf> writer) {
		this.tag.put(id.toString(), encode(this.level, writer));
	}

	static ByteArrayTag encode(ServerLevel level, Consumer<RegistryFriendlyByteBuf> writer) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
		try {
			writer.accept(buf);
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			return new ByteArrayTag(bytes);
		} finally {
			buf.release();
		}
	}
}
