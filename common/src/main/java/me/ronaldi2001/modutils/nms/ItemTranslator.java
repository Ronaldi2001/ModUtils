package me.ronaldi2001.modutils.nms;

import me.ronaldi2001.modutils.nms.AdventureBridge;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.pluginHooks.via.ViaMappings;
import me.ronaldi2001.modutils.pluginHooks.via.ViaVersionHook;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.kyori.adventure.translation.GlobalTranslator;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;

/**
 * Writes items for a client on a different Minecraft version than the server.
 * <p>
 * Items go over the wire as registry ids, which differ between versions, so ids are remapped through ViaVersion's
 * mappings. Data components can't be translated in general, so only the ones that pick a custom model are kept,
 * {@code item_model} (a plain identifier) and {@code custom_model_data}, plus the two that name the item
 * ({@code item_name} and {@code custom_name}) and the {@code lore}. Of the custom data only Polymer's marker (see
 * {@link #POLYMER_STACK}) is kept, so a Polymer client can tell which custom item a stack is.
 */
public final class ItemTranslator {
	/** The custom data key Polymer reads an item's identity from. */
	public static final String POLYMER_STACK = "$polymer:stack";

	private final IntUnaryOperator itemIds;
	private final int itemModelComponentId;
	private final int customModelDataComponentId;
	private final boolean customModelDataAsInt;
	private final int itemNameComponentId;
	private final int customNameComponentId;
	private final int loreComponentId;
	private final int customDataComponentId;
	private final UnaryOperator<Component> nameRenderer;

	/**
	 * @param itemIds              maps a server item id to the client's id, or to a negative number if it has none
	 * @param itemModelComponentId the id of {@code minecraft:item_model} on the client, or -1 if it doesn't exist
	 * @param customModelDataComponentId the id of {@code minecraft:custom_model_data} on the client, or -1
	 * @param customModelDataAsInt true for clients before 1.21.4, where custom model data is a single int instead of
	 *                             the floats/flags/strings/colors record
	 * @param customDataComponentId the id of {@code minecraft:custom_data} on the client, or -1
	 */
	public ItemTranslator(IntUnaryOperator itemIds, int itemModelComponentId, int customModelDataComponentId,
						  boolean customModelDataAsInt, int itemNameComponentId, int customNameComponentId,
						  int loreComponentId, int customDataComponentId) {
		this(itemIds, itemModelComponentId, customModelDataComponentId, customModelDataAsInt, itemNameComponentId,
				customNameComponentId, loreComponentId, customDataComponentId, UnaryOperator.identity());
	}

	private ItemTranslator(IntUnaryOperator itemIds, int itemModelComponentId, int customModelDataComponentId,
						   boolean customModelDataAsInt, int itemNameComponentId, int customNameComponentId,
						   int loreComponentId, int customDataComponentId, UnaryOperator<Component> nameRenderer) {
		this.itemIds = itemIds;
		this.itemModelComponentId = itemModelComponentId;
		this.customModelDataComponentId = customModelDataComponentId;
		this.customModelDataAsInt = customModelDataAsInt;
		this.itemNameComponentId = itemNameComponentId;
		this.customNameComponentId = customNameComponentId;
		this.loreComponentId = loreComponentId;
		this.customDataComponentId = customDataComponentId;
		this.nameRenderer = nameRenderer;
	}

	/**
	 * Item names are usually translatable components that Paper renders per player when it sends an item, which
	 * doesn't happen for items we write ourselves. This renders them for the given locale the same way.
	 */
	public ItemTranslator withLocale(Locale locale) {
		return new ItemTranslator(this.itemIds, this.itemModelComponentId, this.customModelDataComponentId,
				this.customModelDataAsInt, this.itemNameComponentId, this.customNameComponentId, this.loreComponentId,
				this.customDataComponentId,
				name -> AdventureBridge.asVanilla(GlobalTranslator.render(AdventureBridge.asAdventure(name), locale)));
	}

	/**
	 * Has no ViaVersion code of its own: the mappings come from {@link ViaVersionHook}, so this is only ever built
	 * when ViaVersion is installed.
	 *
	 * @return a translator for this player's client version, or null if ViaVersion isn't installed, or the client is
	 * on the server's version, in which case the vanilla codecs can be used as they are.
	 */
	public static @Nullable ItemTranslator forPlayer(ModUtilsPlugin plugin, Player player) {
		ViaVersionHook via = plugin.getViaVersionHook();
		if (via == null) return null;

		ViaMappings m = via.createMappings(player);
		if (m == null) return null;
		return new ItemTranslator(m.itemIds(), m.itemModelComponentId(), m.customModelDataComponentId(),
				m.customModelDataAsInt(), m.itemNameComponentId(), m.customNameComponentId(), m.loreComponentId(),
				m.customDataComponentId())
				.withLocale(ServerApi.locale(player));
	}

	/**
	 * @return the item's id on the client, or -1 if the client has no equivalent
	 */
	public int translateItemId(int serverId) {
		return this.itemIds.applyAsInt(serverId);
	}

	/**
	 * The layout of {@code ItemStack.OPTIONAL_STREAM_CODEC}: count, item id, components. An item with no equivalent
	 * on the client is written as empty.
	 */
	public void writeStack(FriendlyByteBuf buf, ItemStack stack) {
		if (stack.isEmpty()) {
			buf.writeVarInt(0);
			return;
		}

		int id = this.translateItemId(BuiltInRegistries.ITEM.getId(stack.getItem()));
		if (id < 0) {
			buf.writeVarInt(0);
			return;
		}

		buf.writeVarInt(stack.getCount());
		buf.writeVarInt(id);
		this.writeComponents(buf, stack);
	}

	/**
	 * The layout of the newer {@code ItemStackTemplate} used for recipe results: item id, count, components.
	 *
	 * @return false if the client has no equivalent of the item (nothing is written)
	 */
	public boolean writeTemplate(FriendlyByteBuf buf, ItemStack stack) {
		if (stack.isEmpty()) return false;

		int id = this.translateItemId(BuiltInRegistries.ITEM.getId(stack.getItem()));
		if (id < 0) return false;

		buf.writeVarInt(id);
		buf.writeVarInt(stack.getCount());
		this.writeComponents(buf, stack);
		return true;
	}

	private void writeComponents(FriendlyByteBuf buf, ItemStack stack) {
		Identifier model = Nms.itemModel(stack);
		boolean writeModel = model != null && this.itemModelComponentId >= 0;

		CustomModelData modelData = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		boolean writeModelData = modelData != null && this.customModelDataComponentId >= 0;

		Component itemName = Nms.hasNonDefault(stack, DataComponents.ITEM_NAME) ? stack.get(DataComponents.ITEM_NAME) : null;
		boolean writeItemName = itemName != null && this.itemNameComponentId >= 0;

		Component customName = stack.get(DataComponents.CUSTOM_NAME);
		boolean writeCustomName = customName != null && this.customNameComponentId >= 0;

		ItemLore lore = stack.get(DataComponents.LORE);
		boolean writeLore = lore != null && !lore.lines().isEmpty() && this.loreComponentId >= 0;

		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		boolean writeMarker = customData != null && ServerApi.hasKey(customData, POLYMER_STACK) && this.customDataComponentId >= 0;

		int added = (writeMarker ? 1 : 0) + (writeModel ? 1 : 0) + (writeModelData ? 1 : 0) + (writeItemName ? 1 : 0) + (writeCustomName ? 1 : 0)
				+ (writeLore ? 1 : 0);
		buf.writeVarInt(added); // added components
		buf.writeVarInt(0); // removed components

		if (writeMarker) {
			buf.writeVarInt(this.customDataComponentId);
			// Only the marker: the rest of the custom data is the plugins' own business
			CompoundTag marker = new CompoundTag();
			Tag polymerTag = customData.copyTag().get(POLYMER_STACK);
			if (polymerTag != null) marker.put(POLYMER_STACK, polymerTag);
			// custom_data goes over the wire as a plain compound tag
			ByteBufCodecs.COMPOUND_TAG.encode(buf, marker);
		}
		if (writeModel) {
			buf.writeVarInt(this.itemModelComponentId);
			Identifier.STREAM_CODEC.encode(buf, model);
		}
		if (writeModelData) {
			buf.writeVarInt(this.customModelDataComponentId);
			Nms.writeModelData(buf, modelData, this.customModelDataAsInt);
		}
		if (writeItemName) {
			buf.writeVarInt(this.itemNameComponentId);
			this.writeComponent(buf, itemName);
		}
		if (writeCustomName) {
			buf.writeVarInt(this.customNameComponentId);
			this.writeComponent(buf, customName);
		}
		if (writeLore) {
			buf.writeVarInt(this.loreComponentId);
			// A list of components, at most ItemLore.MAX_LINES long
			List<Component> lines = lore.lines();
			int count = Math.min(lines.size(), ItemLore.MAX_LINES);
			buf.writeVarInt(count);
			for (int i = 0; i < count; i++) this.writeComponent(buf, lines.get(i));
		}
	}

	private void writeComponent(FriendlyByteBuf buf, Component component) {
		// Encoded on a registry-aware buffer so the codec has what it needs, then copied into the caller's buffer
		RegistryFriendlyByteBuf temp = new RegistryFriendlyByteBuf(buf.alloc().buffer(), RegistryAccess.EMPTY);
		try {
			ComponentSerialization.STREAM_CODEC.encode(temp, this.nameRenderer.apply(component));
			buf.writeBytes(temp, temp.readerIndex(), temp.readableBytes());
		} finally {
			temp.release();
		}
	}
}
