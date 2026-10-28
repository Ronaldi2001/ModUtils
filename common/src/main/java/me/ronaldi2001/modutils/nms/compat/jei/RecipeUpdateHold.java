package me.ronaldi2001.modutils.nms.compat.jei;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import org.jspecify.annotations.Nullable;


/**
 * Holds back the recipe update Paper sends while a player joins.
 * <p>
 * JEI starts when it receives that packet, so the recipe sync has to arrive before it. Otherwise, JEI starts without
 * the server's recipes, warns that the server doesn't provide any, and only restarts once they arrive. Paper sends
 * the update before any plugin event can run, so it is caught here, on the connection, and sent again after the
 * recipes.
 * <p>
 * This sits in the connection's pipeline and looks at the server's own packet objects, so it doesn't depend on how
 * a packet library names a packet for a client version it may not know.
 */
public final class RecipeUpdateHold extends ChannelOutboundHandlerAdapter {
	static final String NAME = "rawhub_recipe_hold";

	/**
	 * Only the first recipe update of a connection is held, which is the one sent while joining.
	 */
	private volatile boolean holding = true;
	private volatile boolean held;

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		if (this.holding && msg instanceof ClientboundUpdateRecipesPacket) {
			this.holding = false;
			this.held = true;
			promise.setSuccess();
			return;
		}
		ctx.write(msg, promise);
	}

	/**
	 * @return whether a recipe update was actually held back, so one has to be sent
	 */
	boolean wasHeld() {
		return this.held;
	}

	/**
	 * Stops holding anything, and takes the handler out of the pipeline.
	 */
	void release(@Nullable Channel channel) {
		this.holding = false;
		if (channel == null) return;
		try {
			if (channel.pipeline().get(NAME) == this) channel.pipeline().remove(this);
		} catch (Exception ignored) {
			// The connection is already gone
		}
	}
}
