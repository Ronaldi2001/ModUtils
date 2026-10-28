package me.ronaldi2001.modutils.nms.compat.jade;

import me.ronaldi2001.modutils.nms.ServerApi;
import io.netty.buffer.Unpooled;
import me.ronaldi2001.modutils.nms.AdventureBridge;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.nms.Nms;
import me.ronaldi2001.modutils.nms.VersionShim;
import me.ronaldi2001.modutils.nms.compat.jade.providers.ItemStorageSupport;
import net.kyori.adventure.translation.GlobalTranslator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Furnace;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Jade on a 1.20.5 or 1.20.6 server. A client on 1.21 or newer is answered like on a 1.21 server (see
 * {@link Jade121Compat}); this adds Jade 14, the Jade of 1.20.5 and 1.20.6 clients.
 * <p>
 * Jade 14 pings the client with {@code jade:server_ping} (just the server's config string) when it joins, and asks for
 * data with {@code jade:request_block} and {@code jade:request_entity}. A request names its target and nothing else:
 * there is no list of providers to run, the server runs every one that applies and answers with a
 * {@code jade:receive_data} compound tag. The providers keep their data in plain NBT keys, where the newer Jade has
 * them write a binary stream under the provider's id, so they are written here rather than shared.
 */
public class Jade1205Compat extends Jade121Compat {
	private static final String SERVER_PING = "jade:server_ping";
	/**
	 * Jade shows at most this many stacks of a storage.
	 */
	private static final int MAX_STACKS = 54;
	/**
	 * Jade ignores timers further away than this (two days).
	 */
	private static final int MAX_DROP_TICKS = 24000 * 2;

	public Jade1205Compat(ModUtilsPlugin plugin) {
		super(plugin);
	}

	@Override
	protected String[] pingChannels() {
		String[] newer = super.pingChannels();
		String[] all = Arrays.copyOf(newer, newer.length + 1);
		all[newer.length] = SERVER_PING;
		return all;
	}

	private static boolean isJade14(Player player) {
		return player.getListeningPluginChannels().contains(SERVER_PING);
	}

	@Override
	protected void handle(String channel, Player player, ServerPlayer handle, RegistryFriendlyByteBuf in) {
		if (!isJade14(player)) {
			super.handle(channel, player, handle, in);
			return;
		}
		switch (channel) {
			case REQUEST_BLOCK -> this.handleBlockRequest(player, handle, in);
			case REQUEST_ENTITY -> this.handleEntityRequest(player, handle, in);
			default -> {
			}
		}
	}

	@Override
	@SuppressWarnings("resource") // ServerLevel is closeable, but it belongs to the server
	protected void clientReady(Player player, ServerPlayer handle, String channel) {
		if (!SERVER_PING.equals(channel)) {
			super.clientReady(player, handle, channel);
			return;
		}
		if (!this.firstPing(player)) return;

		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), Nms.level(handle).registryAccess());
		try {
			ByteBufCodecs.STRING_UTF8.encode(out, ""); // server config
			this.send(player, SERVER_PING, out);
			this.debug("Sent the ping to " + player.getName());
		} finally {
			out.release();
		}
	}

	private void handleBlockRequest(Player player, ServerPlayer handle, RegistryFriendlyByteBuf in) {
		boolean showDetails = in.readBoolean();
		BlockHitResult hit = VersionShim.readBlockHitResult(in);
		in.readVarInt(); // the block state the client sees, unused
		ItemStack.OPTIONAL_STREAM_CODEC.decode(in); // the item of a fake block, unused
		BlockPos pos = hit.getBlockPos();
		this.processBlock(player, handle, pos, showDetails, new CompoundTag(), true, this::fillBlock);
	}

	private void handleEntityRequest(Player player, ServerPlayer handle, RegistryFriendlyByteBuf in) {
		boolean showDetails = in.readBoolean();
		int entityId = in.readVarInt();
		in.readVarInt(); // part index (multipart entities), unused
		in.readFloat();
		in.readFloat();
		in.readFloat(); // hit vector, unused
		this.processEntity(player, handle, entityId, showDetails, new CompoundTag(), true, this::fillEntity);
	}

	// ------------------------------------------------------------------ blocks

	private void fillBlock(BlockCtx ctx) {
		this.run(ctx, "furnace", this::furnace);
		this.run(ctx, "brewingStand", this::brewingStand);
		this.run(ctx, "beehive", this::beehive);
		this.run(ctx, "jukebox", this::jukebox);
		this.run(ctx, "itemStorage", this::blockStorage);
		this.run(ctx, "customBlockNames", this::blockName);
	}

	private void run(BlockCtx ctx, String feature, Consumer<BlockCtx> provider) {
		if (!this.enabled(feature)) return;
		try {
			provider.accept(ctx);
		} catch (Exception e) {
			this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Jade block provider " + feature + " failed", e);
		}
	}

	private void furnace(BlockCtx ctx) {
		if (!(ctx.blockEntity() instanceof AbstractFurnaceBlockEntity furnace) || furnace.isEmpty()) return;
		if (!(ServerApi.blockState(ctx.bukkitBlock()) instanceof Furnace state)) return;

		RegistryAccess registries = ctx.level().registryAccess();
		ListTag items = new ListTag();
		for (int i = 0; i < 3; i++) items.add(furnace.getItem(i).saveOptional(registries));
		ctx.tag().put("furnace", items);
		ctx.tag().putInt("progress", state.getCookTime());
		ctx.tag().putInt("total", state.getCookTimeTotal());
	}

	private void brewingStand(BlockCtx ctx) {
		if (!(ServerApi.blockState(ctx.bukkitBlock()) instanceof BrewingStand stand)) return;

		CompoundTag brewing = new CompoundTag();
		brewing.putInt("Time", stand.getBrewingTime());
		brewing.putInt("Fuel", stand.getFuelLevel());
		ctx.tag().put("BrewingStand", brewing);
	}

	private void beehive(BlockCtx ctx) {
		if (!(ctx.blockEntity() instanceof BeehiveBlockEntity hive)) return;

		ctx.tag().putByte("Bees", (byte) hive.getOccupantCount());
		ctx.tag().putBoolean("Full", hive.isFull());
	}

	private void jukebox(BlockCtx ctx) {
		if (!(ctx.blockEntity() instanceof JukeboxBlockEntity jukebox) || !ctx.state().getValue(JukeboxBlock.HAS_RECORD)) return;

		ItemStack record = jukebox.getTheItem();
		if (!record.isEmpty()) ctx.tag().put("record", record.save(ctx.level().registryAccess()));
	}

	private void blockStorage(BlockCtx ctx) {
		List<ItemStack> items = ItemStorageSupport.blockItems(ctx, this.itemSources);
		if (items != null) putStorage(ctx.tag(), ctx.level().registryAccess(), items);
	}

	/**
	 * Jade shows a block's custom name when the client's own copy of the block entity has one, but the server never
	 * sends names to clients, so the client never asks. Answer unasked so custom blocks keep their name.
	 */
	private void blockName(BlockCtx ctx) {
		if (!(ctx.blockEntity() instanceof Nameable nameable) || !nameable.hasCustomName()) return;
		Component name = nameable.getCustomName();
		if (name == null) return;

		// Names are translatable keys that only this server can resolve: turn them into text for the player
		Locale locale = ServerApi.locale(ctx.player().getBukkitEntity());
		Component text = AdventureBridge.asVanilla(GlobalTranslator.render(AdventureBridge.asAdventure(name), locale));
		ComponentSerialization.CODEC.encodeStart(ctx.level().registryAccess().createSerializationContext(NbtOps.INSTANCE), text)
				.result().ifPresent(encoded -> ctx.tag().put("given_name", encoded));
	}

	// ------------------------------------------------------------------ entities

	private void fillEntity(EntityCtx ctx) {
		this.run(ctx, "mobGrowth", this::mobGrowth);
		this.run(ctx, "mobBreeding", this::mobBreeding);
		this.run(ctx, "animalOwner", this::animalOwner);
		this.run(ctx, "nextEntityDrop", this::nextEntityDrop);
		this.run(ctx, "entityItemStorage", this::entityStorage);
	}

	private void run(EntityCtx ctx, String feature, Consumer<EntityCtx> provider) {
		if (!this.enabled(feature)) return;
		try {
			provider.accept(ctx);
		} catch (Exception e) {
			this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Jade entity provider " + feature + " failed", e);
		}
	}

	private void mobGrowth(EntityCtx ctx) {
		if (!(ctx.entity() instanceof AgeableMob ageable)) return;

		int time = -ageable.getAge();
		if (time > 0) ctx.tag().putInt("GrowingTime", time);
	}

	private void mobBreeding(EntityCtx ctx) {
		if (!(ctx.entity() instanceof Animal animal)) return;

		int time = animal.getAge();
		if (time > 0) ctx.tag().putInt("BreedingCD", time);
	}

	private void animalOwner(EntityCtx ctx) {
		if (!(ctx.entity().getBukkitEntity() instanceof Tameable tameable)) return;

		UUID ownerId = ServerApi.ownerId(tameable);
		if (ownerId == null) return;

		OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerId);
		if (owner.getName() != null) ctx.tag().putString("OwnerName", owner.getName());
	}

	private void nextEntityDrop(EntityCtx ctx) {
		if (ctx.entity().getBukkitEntity() instanceof Chicken chicken && chicken.isAdult()) {
			int time = ServerApi.eggLayTime(chicken);
			if (time < MAX_DROP_TICKS) ctx.tag().putInt("NextEggIn", time);
		}
	}

	private void entityStorage(EntityCtx ctx) {
		List<ItemStack> items = ItemStorageSupport.entityItems(ctx);
		if (items != null) putStorage(ctx.tag(), ctx.level().registryAccess(), items);
	}

	// ------------------------------------------------------------------ storage

	/**
	 * Writes the stacks as Jade 14 reads an item storage: a list with one group of views, each an item (a stack bigger
	 * than its maximum is saved as one with the real count next to it), and the id of the extension that reads it.
	 */
	private static void putStorage(CompoundTag tag, RegistryAccess registries, List<ItemStack> stacks) {
		ListTag views = new ListTag();
		for (int i = 0; i < stacks.size() && i < MAX_STACKS; i++) {
			ItemStack stack = stacks.get(i);
			int count = stack.getCount();
			boolean oversized = count > stack.getMaxStackSize();
			CompoundTag item = (CompoundTag) (oversized ? stack.copyWithCount(1) : stack).save(registries);
			if (oversized) item.putInt("NewCount", count);
			views.add(item);
		}
		if (views.isEmpty()) return;

		CompoundTag group = new CompoundTag();
		group.put("Views", views);
		ListTag groups = new ListTag();
		groups.add(group);
		tag.put("JadeItemStorage", groups);
		tag.putString("JadeItemStorageUid", "minecraft:item_storage.default");
	}
}
