package me.ronaldi2001.modutils.nms.compat.polymer;

import me.ronaldi2001.modutils.nms.Nms;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
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
				msg = new ServerboundSetCreativeModeSlotPacket(slotNum, this.support.fromCreative(itemStack));
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

	/**
	 * A creative slot the server can't read (a client mod's item, whose id the server doesn't have) would end the
	 * connection. The slot is dropped instead, and the player's inventory sent again so the item doesn't stay on their cursor.
	 */
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		if (cause instanceof DecoderException && cause.getMessage() != null && cause.getMessage().contains("set_creative_mode_slot")) {
			this.discardUnread(ctx);
			this.support.creativeSlotRejected(this.session, cause);
			return;
		}
		ctx.fireExceptionCaught(cause);
	}

	/**
	 * The decoder keeps what it did not read of the broken packet, which would be taken for the start of the next one.
	 */
	private void discardUnread(ChannelHandlerContext ctx) {
		try {
			if (!(ctx.pipeline().get("decoder") instanceof ByteToMessageDecoder decoder)) return;

			Method internalBuffer = ByteToMessageDecoder.class.getDeclaredMethod("internalBuffer");
			internalBuffer.setAccessible(true);
			ByteBuf buffer = (ByteBuf) internalBuffer.invoke(decoder);
			buffer.skipBytes(buffer.readableBytes());
		} catch (ReflectiveOperationException | RuntimeException e) {
			this.support.warn("Could not clear the packet that failed to decode for " + this.session.id + ": " + e);
		}
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
