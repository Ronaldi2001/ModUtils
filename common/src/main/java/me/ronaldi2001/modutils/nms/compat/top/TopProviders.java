package me.ronaldi2001.modutils.nms.compat.top;

import me.ronaldi2001.modutils.nms.ServerApi;
import me.ronaldi2001.modutils.nms.AdventureBridge;
import me.ronaldi2001.modutils.nms.compat.jade.BlockCtx;
import me.ronaldi2001.modutils.nms.compat.jade.BlockItemSource;
import me.ronaldi2001.modutils.nms.compat.jade.EntityCtx;
import me.ronaldi2001.modutils.nms.compat.jade.providers.ItemStorageSupport;
import me.ronaldi2001.modutils.translations.Argument;
import me.ronaldi2001.modutils.translations.TransUtils;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.translation.GlobalTranslator;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Furnace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Tameable;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What the probe shows for a block or an entity: the same things the Jade providers add, in TOP's layout. The server
 * builds the whole picture, TOP's client only draws it.
 * <p>
 * Each part can be switched off under {@code top.features} in the config; the header (what the target is called) cannot.
 */
final class TopProviders {
	/** Slots in a row of stored items, like a chest. */
	private static final int ITEMS_PER_ROW = 9;
	private static final int ORANGE = 0xffdd8822;
	private static final int RED = 0xffcc3333;

	private TopProviders() {
	}

	static void block(BlockCtx ctx, ProbeInfo info, ConfigurationSection features, List<BlockItemSource> itemSources) {
		ProbeInfo.Panel root = info.root();
		header(ctx, root, features);

		if (enabled(features, "cropGrowth")) cropGrowth(ctx, root);
		if (enabled(features, "furnace")) furnace(ctx, root);
		if (enabled(features, "brewingStand")) brewingStand(ctx, root);
		if (enabled(features, "itemStorage")) {
			List<ItemStack> items = ItemStorageSupport.blockItems(ctx, itemSources);
			if (items != null) itemRows(ctx.player(), root, items);
		}
	}

	static void entity(EntityCtx ctx, ProbeInfo info, ConfigurationSection features) {
		ProbeInfo.Panel root = info.root();
		var entity = ctx.entity();
		var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());

		root.text(rendered(ctx, entity.getDisplayName()).copy().withStyle(ChatFormatting.WHITE));
		root.text(modName(key.getNamespace()));

		if (enabled(features, "mobHealth") && entity instanceof LivingEntity living && living.getMaxHealth() > 0) {
			int health = (int) Math.ceil(living.getHealth());
			int max = (int) Math.ceil(living.getMaxHealth());
			root.progress(health, max, ProbeInfo.Progress.create().filled(RED).background(0xff330000)
					.prefix(lang(ctx.player(), "health")));
		}

		if (enabled(features, "animalOwner") && entity.getBukkitEntity() instanceof Tameable tameable) {
			UUID ownerId = ServerApi.ownerId(tameable);
			OfflinePlayer owner = ownerId == null ? null : Bukkit.getOfflinePlayer(ownerId);
			if (owner != null && owner.getName() != null) {
				root.text(lang(ctx.player(), "owner", Argument.string("owner_name", owner.getName())));
			}
		}

		if (enabled(features, "entityItemStorage")) {
			List<ItemStack> items = ItemStorageSupport.entityItems(ctx);
			if (items != null) itemRows(ctx.player(), root, items);
		}
	}

	/** The block's icon and name with the mod it comes from under it, the way TOP shows any block. */
	private static void header(BlockCtx ctx, ProbeInfo.Panel root, ConfigurationSection features) {
		Block block = ctx.state().getBlock();
		var key = BuiltInRegistries.BLOCK.getKey(block);
		Component mod = modName(key.getNamespace());

		Component customName = null;
		if (enabled(features, "customBlockNames") && ctx.blockEntity() instanceof Nameable nameable && nameable.hasCustomName()) {
			Component name = nameable.getCustomName();
			if (name != null) customName = rendered(ServerApi.locale(ctx.player().getBukkitEntity()), name);
		}

		ItemStack icon = new ItemStack(block.asItem());
		if (icon.isEmpty()) {
			// A block that has no item (fire, a portal, ...): only its name
			root.vertical()
					.text((customName != null ? customName : Component.translatable(block.getDescriptionId())).copy().withStyle(ChatFormatting.WHITE))
					.text(mod);
			return;
		}

		ProbeInfo.Panel row = root.horizontal(ProbeInfo.ALIGN_CENTER).item(icon);
		ProbeInfo.Panel text = row.vertical();
		if (customName != null) {
			text.text(customName.copy().withStyle(ChatFormatting.WHITE));
		} else {
			text.itemLabel(icon);
		}
		text.text(mod);
	}

	private static void cropGrowth(BlockCtx ctx, ProbeInfo.Panel root) {
		Block block = ctx.state().getBlock();
		if (!(block instanceof CropBlock || block instanceof NetherWartBlock || block instanceof SweetBerryBushBlock
				|| block instanceof CocoaBlock)) return;

		for (Property<?> property : ctx.state().getProperties()) {
			if (!(property instanceof IntegerProperty age) || !age.getName().equals("age")) continue;

			int value = ctx.state().getValue(age);
			int max = Collections.max(age.getPossibleValues());
			if (value >= max) {
				root.text(lang(ctx.player(), "fully_grown"));
			} else {
				root.text(lang(ctx.player(), "growth", Argument.numeric("percent", value * 100 / max)));
			}
			return;
		}
	}

	private static void furnace(BlockCtx ctx, ProbeInfo.Panel root) {
		if (!(ctx.blockEntity() instanceof AbstractFurnaceBlockEntity furnace)) return;
		if (!(ServerApi.blockState(ctx.bukkitBlock()) instanceof Furnace state)) return;

		ProbeInfo.Panel slots = root.horizontal(ProbeInfo.ALIGN_CENTER);
		for (int slot = 0; slot < 3; slot++) {
			slots.item(furnace.getItem(slot));
		}
		if (slots.isEmpty()) return;

		int total = state.getCookTimeTotal();
		int progress = state.getCookTime();
		if (total > 0 && progress > 0) {
			root.progress(progress * 100L / total, 100, ProbeInfo.Progress.create().filled(ORANGE)
					.prefix(lang(ctx.player(), "cooking")).suffix(Component.literal("%")));
		}
	}

	private static void brewingStand(BlockCtx ctx, ProbeInfo.Panel root) {
		if (!(ServerApi.blockState(ctx.bukkitBlock()) instanceof BrewingStand stand)) return;

		root.text(lang(ctx.player(), "fuel", Argument.numeric("fuel_level", stand.getFuelLevel())));
		int time = stand.getBrewingTime();
		if (time > 0) {
			root.text(lang(ctx.player(), "brewing_time", Argument.numeric("ticks", time)));
		}
	}

	/** Stored stacks in rows, like the inventory they come from. */
	private static void itemRows(ServerPlayer player, ProbeInfo.Panel root, List<ItemStack> items) {
		if (items.isEmpty()) {
			root.text(lang(player, "empty"));
			return;
		}
		// A count above the stack size (a custom storage's total) is not drawn by TOP in the player's number format, so
		// that item gets a line of its own with the formatted amount next to the icon
		NumberFormat format = NumberFormat.getIntegerInstance(ServerApi.locale(player.getBukkitEntity()));
		List<ItemStack> grid = new ArrayList<>();
		for (ItemStack stack : items) {
			if (stack.getCount() <= stack.getMaxStackSize()) {
				grid.add(stack);
				continue;
			}
			ItemStack icon = stack.copyWithCount(1);
			root.horizontal(ProbeInfo.ALIGN_CENTER).item(icon)
					.text(Component.literal(format.format(stack.getCount())).withStyle(ChatFormatting.WHITE));
		}

		ProbeInfo.Panel row = null;
		for (int i = 0; i < grid.size(); i++) {
			if (i % ITEMS_PER_ROW == 0) row = root.horizontal();
			row.item(grid.get(i));
		}
	}

	/** A message of the lang file under {@code modCompat.top}, in the player's language, as the server's own text type. */
	private static Component lang(ServerPlayer player, String key, ComponentLike... args) {
		return AdventureBridge.asVanilla(TransUtils.translatableManual(
				ServerApi.locale(player.getBukkitEntity()), "modCompat.top." + key, args));
	}

	/** The mod a block or entity comes from, as TOP shows it: blue and in italics. */
	private static Component modName(String namespace) {
		String name = namespace.equals("minecraft") ? "Minecraft"
				: namespace.substring(0, 1).toUpperCase(Locale.ROOT) + namespace.substring(1);
		return Component.literal(name).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC);
	}

	/** Names are translatable keys that only this server can resolve: turn them into text for the player. */
	private static Component rendered(EntityCtx ctx, Component name) {
		return rendered(ServerApi.locale(ctx.player().getBukkitEntity()), name);
	}

	private static Component rendered(Locale locale, Component name) {
		return AdventureBridge.asVanilla(GlobalTranslator.render(AdventureBridge.asAdventure(name), locale));
	}

	private static boolean enabled(ConfigurationSection features, String key) {
		return features == null || features.getBoolean(key, true);
	}
}
