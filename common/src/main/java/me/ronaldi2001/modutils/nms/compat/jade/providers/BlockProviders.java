package me.ronaldi2001.modutils.nms.compat.jade.providers;

import me.ronaldi2001.modutils.nms.JukeboxNames;
import me.ronaldi2001.modutils.nms.ServerApi;
import me.ronaldi2001.modutils.nms.Nms;
import me.ronaldi2001.modutils.nms.compat.jade.BlockCtx;
import me.ronaldi2001.modutils.nms.compat.jade.BlockItemSource;
import me.ronaldi2001.modutils.nms.compat.jade.JadeBlockProvider;
import me.ronaldi2001.modutils.nms.AdventureBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.translation.GlobalTranslator;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Nameable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import org.bukkit.block.Lockable;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Furnace;
import org.bukkit.loot.Lootable;

import java.util.List;
import java.util.Locale;

/** Server-side halves of Jade's vanilla/universal block providers. Data layouts mirror Jade's stream codecs. */
public final class BlockProviders {
	private BlockProviders() {
	}

	/**
	 * @param customSources custom storages to consult first; add new ones here
	 */
	public static List<JadeBlockProvider> all(List<BlockItemSource> customSources) {
		return List.of(
				new FurnaceProvider(),
				new BrewingStandProvider(),
				new BeehiveProvider(),
				new JukeboxProvider(),
				new ItemStorageProvider(customSources),
				new ObjectNameProvider()
		);
	}

	private static final class FurnaceProvider implements JadeBlockProvider {
		@Override
		public Identifier id() {
			return JadeIds.FURNACE;
		}

		@Override
		public String configKey() {
			return "furnace";
		}

		@Override
		public void append(BlockCtx ctx) {
			if (!(ctx.blockEntity() instanceof AbstractFurnaceBlockEntity furnace)) return;
			if (!(ServerApi.blockState(ctx.bukkitBlock()) instanceof Furnace state)) return;

			int progress = state.getCookTime();
			int total = state.getCookTimeTotal();
			List<ItemStack> inventory = List.of(furnace.getItem(0), furnace.getItem(1), furnace.getItem(2));
			ctx.put(id(), buf -> {
				buf.writeVarInt(progress);
				buf.writeVarInt(total);
				buf.writeVarInt(inventory.size());
				inventory.forEach(stack -> ctx.items().write(buf, stack));
			});
		}
	}

	private static final class BrewingStandProvider implements JadeBlockProvider {
		@Override
		public Identifier id() {
			return JadeIds.BREWING_STAND;
		}

		@Override
		public String configKey() {
			return "brewingStand";
		}

		@Override
		public void append(BlockCtx ctx) {
			if (!(ServerApi.blockState(ctx.bukkitBlock()) instanceof BrewingStand stand)) return;

			int fuel = stand.getFuelLevel();
			int time = stand.getBrewingTime();
			ctx.put(id(), buf -> {
				buf.writeVarInt(fuel);
				buf.writeVarInt(time);
			});
		}
	}

	private static final class BeehiveProvider implements JadeBlockProvider {
		@Override
		public Identifier id() {
			return JadeIds.BEEHIVE;
		}

		@Override
		public String configKey() {
			return "beehive";
		}

		@Override
		public void append(BlockCtx ctx) {
			if (!(ctx.blockEntity() instanceof BeehiveBlockEntity hive)) return;

			// Positive = full, negative = not full; magnitude is the bee count
			int bees = hive.getOccupantCount();
			ctx.put(id(), buf -> buf.writeByte(hive.isFull() ? bees : -bees));
		}
	}

	private static final class JukeboxProvider implements JadeBlockProvider {
		@Override
		public Identifier id() {
			return JadeIds.JUKEBOX;
		}

		@Override
		public String configKey() {
			return "jukebox";
		}

		@Override
		public void append(BlockCtx ctx) {
			if (!(ctx.blockEntity() instanceof JukeboxBlockEntity jukebox)) return;
			if (!ctx.state().getValue(JukeboxBlock.HAS_RECORD)) return;

			ItemStack record = jukebox.getTheItem();

			// A client on another version only gets the components ItemTranslator can write and fills in the rest from the
			// item's defaults: the disc would play its vanilla song there, and Jade shows that song's title instead of the
			// custom one. So a disc with a song of its own is sent as a plain item named after the song, which is all Jade's
			// "Now playing" line needs.
			net.minecraft.network.chat.Component title = JukeboxNames.songName(record, ctx.level().registryAccess());
			if (title != null) {
				record = new ItemStack(Items.PAPER);
				record.set(DataComponents.ITEM_NAME, title);
			}

			ItemStack shown = record;
			ctx.put(id(), buf -> ctx.items().write(buf, shown));
		}
	}

	private static final class ItemStorageProvider implements JadeBlockProvider {
		private final List<BlockItemSource> sources;

		private ItemStorageProvider(List<BlockItemSource> sources) {
			this.sources = sources;
		}

		@Override
		public Identifier id() {
			return JadeIds.ITEM_STORAGE;
		}

		@Override
		public String configKey() {
			return "itemStorage";
		}

		@Override
		public void append(BlockCtx ctx) {
			List<ItemStack> items = ItemStorageSupport.blockItems(ctx, this.sources);
			if (items != null) ctx.put(id(), buf -> ItemStorageSupport.write(buf, ctx.items(), items));
		}
	}

	/**
	 * Jade shows a block's custom name when the client's own copy of the block entity has one, but the server never
	 * sends names to clients, so the client never asks. Answer unasked so custom blocks keep their name.
	 */
	private static final class ObjectNameProvider implements JadeBlockProvider {
		@Override
		public Identifier id() {
			return JadeIds.OBJECT_NAME;
		}

		@Override
		public String configKey() {
			return "customBlockNames";
		}

		@Override
		public boolean alwaysRun() {
			return true;
		}

		@Override
		public void append(BlockCtx ctx) {
			if (!(ctx.blockEntity() instanceof Nameable nameable) || !nameable.hasCustomName()) return;
			net.minecraft.network.chat.Component name = nameable.getCustomName();
			if (name == null) return;

			// Names are translatable keys that only this server can resolve: turn them into text for the player
			Locale locale = ServerApi.locale(ctx.player().getBukkitEntity());
			Component rendered = GlobalTranslator.render(AdventureBridge.asAdventure(name), locale);
			net.minecraft.network.chat.Component text = AdventureBridge.asVanilla(rendered);
			ctx.put(id(), buf -> ComponentSerialization.STREAM_CODEC.encode(buf, text));
		}
	}
}
