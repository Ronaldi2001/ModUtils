package me.ronaldi2001.modutils.nms.compat.jade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.function.Consumer;

/** Everything a {@link JadeEntityProvider} needs to answer a Jade entity request. */
public record EntityCtx(
		ServerPlayer player,
		ServerLevel level,
		Entity entity,
		boolean showDetails,
		CompoundTag tag,
		ItemWriter items,
		/** Whether the client is on a Jade from before 1.21.2 (protocol 7), which writes a few things differently. */
		boolean beforeJade7
) {
	public void put(Identifier id, Consumer<RegistryFriendlyByteBuf> writer) {
		this.tag.put(id.toString(), BlockCtx.encode(this.level, writer));
	}
}
