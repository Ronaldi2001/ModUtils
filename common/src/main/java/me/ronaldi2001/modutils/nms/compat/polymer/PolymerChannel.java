package me.ronaldi2001.modutils.nms.compat.polymer;

import me.ronaldi2001.modutils.nms.Nms;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Sits on one connection, looking at the server's own packet objects (so it doesn't depend on how a packet library
 * names packets): answers Polymer's packets from the client, and, once the client has been told about the custom
 * items, adds the marker Polymer clients look for to the stacks sent to them, taking it off again on the way back.
 */
final class PolymerChannel extends ChannelDuplexHandler {
	static final String NAME = "modcompat_polymer";

	private final PolymerSupport support;
	private final PolymerSupport.Session session;

	PolymerChannel(PolymerSupport support, PolymerSupport.Session session) {
		this.support = support;
		this.session = session;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof ServerboundCustomPayloadPacket(
				net.minecraft.network.protocol.common.custom.CustomPacketPayload payload1
		) && payload1 instanceof DiscardedPayload payload
				&& payload.id().getNamespace().equals("polymer")) {
			this.support.handle(this.session, payload.id(), Nms.payloadData(payload));
			return; // not for the server
		}

		if (this.session.synced && msg instanceof ServerboundSetCreativeModeSlotPacket(short slotNum, ItemStack itemStack)) {
			try {
				msg = new ServerboundSetCreativeModeSlotPacket(slotNum, this.support.untag(itemStack));
			} catch (Throwable e) {
				this.support.warn("Could not clean a creative slot for " + this.session.id + ": " + e);
			}
		}
		if (this.session.synced) {
			try {
				msg = Nms.untagClick(msg, stack -> this.support.tag(this.session, stack), this.support::untag);
			} catch (Throwable e) {
				this.support.warn("Could not clean a container click for " + this.session.id + ": " + e);
			}
		}
		ctx.fireChannelRead(msg);
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		if (this.session.synced) {
			try {
				msg = this.tagged(msg);
			} catch (Throwable e) {
				this.support.warn("Could not mark items for " + this.session.id + ": " + e);
			}
		}
		ctx.write(msg, promise);
	}

	/**
	 * The packet with its stacks marked, or the same packet if it carries none that need it.
	 */
	private Object tagged(Object msg) {
		if (msg instanceof ClientboundContainerSetContentPacket packet) {
			boolean changed = false;
			List<ItemStack> items = new ArrayList<>(Nms.items(packet).size());
			for (ItemStack stack : Nms.items(packet)) {
				ItemStack marked = this.support.tag(this.session, stack);
				changed |= marked != stack;
				items.add(marked);
			}
			ItemStack carried = this.support.tag(this.session, Nms.carried(packet));
			if (!changed && carried == Nms.carried(packet)) return msg;
			return Nms.contentPacket(Nms.containerId(packet), Nms.stateId(packet), items, carried);
		}
		if (msg instanceof ClientboundContainerSetSlotPacket packet) {
			ItemStack marked = this.support.tag(this.session, packet.getItem());
			if (marked == packet.getItem()) return msg;
			return new ClientboundContainerSetSlotPacket(packet.getContainerId(), packet.getStateId(), packet.getSlot(), marked);
		}
		return Nms.tagSingleStack(msg, stack -> this.support.tag(this.session, stack));
	}
}
