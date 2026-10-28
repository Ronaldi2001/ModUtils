package me.ronaldi2001.modutils.nms.compat.jade.providers;

import me.ronaldi2001.modutils.nms.ServerApi;
import me.ronaldi2001.modutils.nms.Nms;
import me.ronaldi2001.modutils.nms.compat.jade.ItemWriter;
import net.minecraft.core.component.DataComponentPatch;
import org.bukkit.loot.Lootable;
import org.bukkit.block.Lockable;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.ChestBlock;
import me.ronaldi2001.modutils.nms.compat.jade.EntityCtx;
import me.ronaldi2001.modutils.nms.compat.jade.BlockItemSource;
import me.ronaldi2001.modutils.nms.compat.jade.BlockCtx;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared by every provider that fills Jade's item storage view (blocks and entities alike). */
public final class ItemStorageSupport {
	/** Same cap as Jade's ItemCollector.MAX_SIZE. */
	private static final int MAX_STACKS = 54;

	private record StackKey(Item item, DataComponentPatch components) {
	}

	private ItemStorageSupport() {
	}

	/** Merges identical stacks like Jade does, optionally sorted by amount. */
	public static List<ItemStack> collect(Container container, boolean sort) {
		Map<StackKey, Integer> merged = new LinkedHashMap<>();
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (stack.isEmpty()) continue;
			if (Nms.hidesTooltip(stack)) continue;
			merged.merge(new StackKey(stack.getItem(), stack.getComponentsPatch()), stack.getCount(), Integer::sum);
		}

		List<Map.Entry<StackKey, Integer>> entries = new ArrayList<>(merged.entrySet());
		if (sort) entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

		List<ItemStack> result = new ArrayList<>();
		for (Map.Entry<StackKey, Integer> entry : entries) {
			if (result.size() >= MAX_STACKS) break;
			ItemStack stack = new ItemStack(entry.getKey().item(), entry.getValue());
			stack.applyComponents(entry.getKey().components());
			result.add(stack);
		}
		return result;
	}

	/**
	 * What a block shows as its item storage: a custom storage's items first (they live outside vanilla containers), then
	 * the block's own container. A container with an ungenerated loot table or a lock sets a flag in the reply instead.
	 *
	 * @return null if there is nothing to show
	 */
	public static List<ItemStack> blockItems(BlockCtx ctx, List<BlockItemSource> customSources) {
		for (BlockItemSource source : customSources) {
			List<ItemStack> items = source.itemsAt(ctx);
			if (items != null) return items;
		}

		// The client shows furnace contents with its own furnace provider
		if (ctx.blockEntity() == null || ctx.blockEntity() instanceof AbstractFurnaceBlockEntity) return null;

		org.bukkit.block.BlockState state = ServerApi.blockState(ctx.bukkitBlock());
		if (state instanceof Lootable lootable && ServerApi.hasLootTable(lootable)) {
			ctx.tag().putBoolean("Loot", true);
			return null;
		}
		boolean privileged = ctx.player().isCreative() || ctx.player().isSpectator();
		if (!privileged && state instanceof Lockable lockable && lockable.isLocked()) {
			ctx.tag().putBoolean("Locked", true);
			return null;
		}

		Container container = findContainer(ctx);
		if (container == null) return null;
		return collect(container, Nms.getBoolean(ctx.tag(), "SortItems", false));
	}

	private static Container findContainer(BlockCtx ctx) {
		if (ctx.blockEntity() instanceof EnderChestBlockEntity) {
			return ctx.player().getEnderChestInventory();
		}
		if (ctx.blockEntity() instanceof ChestBlockEntity && ctx.state().getBlock() instanceof ChestBlock chest) {
			// Resolves double chests into one combined container
			return ChestBlock.getContainer(chest, ctx.state(), ctx.level(), ctx.pos(), true);
		}
		return ctx.blockEntity() instanceof Container container ? container : null;
	}

	/**
	 * What an entity container (minecart chest, chest boat, ...) shows as its item storage. One with an ungenerated loot
	 * table sets a flag in the reply instead.
	 *
	 * @return null if there is nothing to show
	 */
	public static List<ItemStack> entityItems(EntityCtx ctx) {
		if (!(ctx.entity() instanceof Container container)) return null;

		if (ctx.entity() instanceof ContainerEntity containerEntity && Nms.hasLootTable(containerEntity)) {
			ctx.tag().putBoolean("Loot", true);
			return null;
		}
		return collect(container, Nms.getBoolean(ctx.tag(), "SortItems", false));
	}

	/** Writes the stacks as a single view group, the layout Jade's item storage client provider reads. */
	static void write(RegistryFriendlyByteBuf buf, ItemWriter items, List<ItemStack> stacks) {
		Identifier.STREAM_CODEC.encode(buf, JadeIds.ITEM_STORAGE_DEFAULT);
		buf.writeVarInt(1); // one view group
		buf.writeVarInt(Math.min(stacks.size(), MAX_STACKS));
		for (int i = 0; i < stacks.size() && i < MAX_STACKS; i++) {
			items.write(buf, stacks.get(i));
		}
		buf.writeBoolean(false); // group id
		buf.writeBoolean(false); // group extra data
	}
}
