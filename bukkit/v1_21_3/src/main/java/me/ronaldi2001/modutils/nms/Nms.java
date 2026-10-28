package me.ronaldi2001.modutils.nms;

import net.minecraft.core.HolderSet;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import java.util.function.UnaryOperator;
import java.util.Collection;
import java.util.ArrayList;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The Minecraft calls that changed between versions, as they are in 1.21.3. Unlike the newest form (common), this is a
 * complete copy: the older versions do not stack on each other.
 */
public final class Nms {
	private Nms() {
	}

	/** The level a player is in. */
	public static ServerLevel level(ServerPlayer player) {
		return player.serverLevel();
	}

	/** The name of the Minecraft version, e.g. {@code 1.21.8}. */
	public static String versionName() {
		return SharedConstants.getCurrentVersion().getName();
	}

	// ------------------------------------------------------------------ items

	/** Whether the stack has the component, set to something other than the item's default. */
	public static boolean hasNonDefault(ItemStack stack, DataComponentType<?> type) {
		// An entry in the patch that is not a removal
		Optional<?> entry = stack.getComponentsPatch().get(type);
		return entry != null && entry.isPresent();
	}

	/** Whether the stack hides its tooltip. */
	public static boolean hidesTooltip(ItemStack stack) {
		return stack.has(DataComponents.HIDE_TOOLTIP);
	}

	/**
	 * Writes a custom model data component the way a client reads it.
	 *
	 * @param clientInt true for clients before 1.21.4, where it is a single int; newer ones read the record
	 */
	public static void writeModelData(FriendlyByteBuf buf, CustomModelData data, boolean clientInt) {
		if (clientInt) {
			buf.writeVarInt(data.value());
		} else {
			// The server's component is one int; clients from 1.21.4 read floats, flags, strings and colors
			buf.writeVarInt(1);
			buf.writeFloat(data.value());
			buf.writeVarInt(0);
			buf.writeVarInt(0);
			buf.writeVarInt(0);
		}
	}

	// ------------------------------------------------------------------ recipes

	public static boolean isEmpty(Ingredient ingredient) {
		return ingredient.items().isEmpty();
	}

	public static Stream<Holder<Item>> items(Ingredient ingredient) {
		return ingredient.items().stream();
	}

	/** The base ingredient of a smithing recipe, or null if it has none (it is optional before 1.21.5). */
	public static @Nullable Ingredient smithingBase(SmithingTransformRecipe recipe) {
		return recipe.baseIngredient().orElse(null);
	}

	// ------------------------------------------------------------------ NBT

	public static Set<String> keys(CompoundTag tag) {
		return tag.getAllKeys();
	}

	public static boolean getBoolean(CompoundTag tag, String key, boolean fallback) {
		return tag.contains(key) ? tag.getBoolean(key) : fallback;
	}

	public static CompoundTag getCompound(CompoundTag tag, String key) {
		return tag.getCompound(key);
	}

	public static int intValue(IntTag tag) {
		return tag.getAsInt();
	}

	public static byte byteValue(ByteTag tag) {
		return tag.getAsByte();
	}

	// ------------------------------------------------------------------ packets

	/** A custom payload packet for a channel this version has no codec for. */
	public static Packet<?> customPayload(ResourceLocation channel, byte[] data) {
		try {
			// Spigot's DiscardedPayload takes a ByteBuf where Paper's takes the bytes
			java.lang.reflect.Constructor<?> constructor = DiscardedPayload.class.getDeclaredConstructors()[0];
			Object bytes = constructor.getParameterTypes()[1] == byte[].class ? data : io.netty.buffer.Unpooled.wrappedBuffer(data);
			return new ClientboundCustomPayloadPacket((DiscardedPayload) constructor.newInstance(channel, bytes));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not build a custom payload", e);
		}
	}

	/** The bytes of a custom payload that arrived on a channel this version has no codec for. */
	public static byte[] payloadData(DiscardedPayload payload) {
		try {
			Object data = DiscardedPayload.class.getMethod("data").invoke(payload);
			if (data instanceof byte[] bytes) return bytes;
			io.netty.buffer.ByteBuf buf = (io.netty.buffer.ByteBuf) data; // Spigot hands out a ByteBuf
			byte[] bytes = new byte[buf.readableBytes()];
			buf.getBytes(buf.readerIndex(), bytes);
			return bytes;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not read a custom payload", e);
		}
	}

	public static int containerId(ClientboundContainerSetContentPacket packet) {
		return packet.getContainerId();
	}

	public static int stateId(ClientboundContainerSetContentPacket packet) {
		return packet.getStateId();
	}

	public static List<ItemStack> items(ClientboundContainerSetContentPacket packet) {
		return packet.getItems();
	}

	public static ItemStack carried(ClientboundContainerSetContentPacket packet) {
		return packet.getCarriedItem();
	}

	public static ClientboundContainerSetContentPacket contentPacket(int containerId, int stateId, List<ItemStack> items,
																	  ItemStack carried) {
		NonNullList<ItemStack> list = NonNullList.withSize(items.size(), ItemStack.EMPTY);
		for (int i = 0; i < items.size(); i++) list.set(i, items.get(i));
		return new ClientboundContainerSetContentPacket(containerId, stateId, list, carried);
	}

	// ------------------------------------------------------------------ 1.21.1 and 1.21 differences

	/** The stack's custom item model, or null if it has none. There is no such component before 1.21.2. */
	public static @Nullable ResourceLocation itemModel(ItemStack stack) {
		return hasNonDefault(stack, DataComponents.ITEM_MODEL) ? stack.get(DataComponents.ITEM_MODEL) : null;
	}

	/** The key of a recipe. */
	public static ResourceLocation recipeId(RecipeHolder<?> holder) {
		return holder.id().location();
	}

	/**
	 * The stacks an ingredient takes if it matches items by their components (Paper's exact choice, which is how plugins
	 * make a recipe take a custom item), or null for an ingredient that is a plain set of items.
	 */
	@SuppressWarnings("unchecked")
	public static @Nullable List<ItemStack> exactStacks(Ingredient ingredient) {
		if (!ingredient.isExact()) return null;

		try {
			// Called by name: Spigot's returns a List where Paper's returns a Set
			Collection<ItemStack> stacks = (Collection<ItemStack>) Ingredient.class.getMethod("itemStacks").invoke(ingredient);
			return stacks == null ? null : new ArrayList<>(stacks);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not read the stacks of an ingredient", e);
		}
	}

	/** The ingredients of a recipe in the order its packet has them, with null where there is none. */
	public static List<@Nullable Ingredient> ingredientsOf(Recipe<?> recipe) {
		List<@Nullable Ingredient> out = new ArrayList<>();
		switch (recipe) {
			case ShapedRecipe shaped -> {
				for (Optional<Ingredient> ingredient : shaped.getIngredients()) out.add(ingredient.orElse(null));
			}
			case SmithingTransformRecipe smithing -> {
				out.add(smithing.templateIngredient().orElse(null));
				out.add(smithingBase(smithing));
				out.add(smithing.additionIngredient().orElse(null));
			}
			// Cooking recipes are single-item recipes too
			case SingleItemRecipe single -> out.add(single.input());
			default ->
				// Shapeless and transmute: the ingredients in the order the packet has them
					out.addAll(recipe.placementInfo().ingredients());
		}
		return out;
	}

	/** Whether the entity container has a loot table set (it has not been opened yet). */
	public static boolean hasLootTable(ContainerEntity entity) {
		return entity.getContainerLootTable() != null;
	}

	/**
	 * The packets that carry a single stack outside a container (the cursor, an inventory slot) with their stack run
	 * through {@code tag}, or the same packet if it is none of them. They only exist from 1.21.2.
	 */
	public static Object tagSingleStack(Object msg, UnaryOperator<ItemStack> tag) {
		if (msg instanceof ClientboundSetCursorItemPacket(ItemStack contents)) {
			ItemStack marked = tag.apply(contents);
			return marked == contents ? msg : new ClientboundSetCursorItemPacket(marked);
		}
		if (msg instanceof ClientboundSetPlayerInventoryPacket(int slot, ItemStack contents)) {
			ItemStack marked = tag.apply(contents);
			return marked == contents ? msg : new ClientboundSetPlayerInventoryPacket(slot, marked);
		}
		return msg;
	}


	/**
	 * What the client sends back after a click, with the marker {@link #tagSingleStack} adds taken off its stacks. The server
	 * keeps the client's stacks as what the client has, so ones that still carry the marker never equal its own and it keeps
	 * sending the container again, which shows as ghost items. Any other packet is returned as it is.
	 */
	public static Object untagClick(Object msg, UnaryOperator<ItemStack> tag, UnaryOperator<ItemStack> untag) {
		if (!(msg instanceof net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet)) return msg;

		ItemStack carried = untag.apply(packet.getCarriedItem());
		boolean changed = carried != packet.getCarriedItem();
		it.unimi.dsi.fastutil.ints.Int2ObjectMap<ItemStack> slots = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
		for (it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<ItemStack> entry : packet.getChangedSlots().int2ObjectEntrySet()) {
			ItemStack stack = untag.apply(entry.getValue());
			changed |= stack != entry.getValue();
			slots.put(entry.getIntKey(), stack);
		}
		if (!changed) return msg;
		return new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(packet.getContainerId(), packet.getStateId(),
				packet.getSlotNum(), packet.getButtonNum(), packet.getClickType(), carried, slots);
	}

	// ------------------------------------------------------------------ JEI recipe sync

	/** The packet JEI (re)starts on: the vanilla recipe update, which carries no recipes from 1.21.2 on. */
	public static Packet<?> recipeUpdate(RecipeManager recipes) {
		return new ClientboundUpdateRecipesPacket(recipes.getSynchronizedItemProperties(), recipes.getSynchronizedStonecutterRecipes());
	}

	/** Writes the key of a recipe. */
	public static void writeRecipeId(FriendlyByteBuf buf, RecipeHolder<?> holder) {
		buf.writeResourceKey(holder.id());
	}

	/** Whether the recipe has an ingredient with no items, which would crash a client that is sent it. */
	public static boolean hasEmptyIngredient(Recipe<?> recipe) {
		for (Ingredient ingredient : recipe.placementInfo().ingredients()) {
			if (isEmpty(ingredient)) return true;
		}
		return false;
	}

	/** The registry ids of the items in an item tag (none if the tag does not exist). */
	public static List<Integer> tagItemIds(ResourceLocation tag) {
		List<Integer> ids = new ArrayList<>();
		BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, tag)).ifPresent((HolderSet.Named<Item> named) ->
				named.forEach(holder -> ids.add(BuiltInRegistries.ITEM.getId(holder.value()))));
		return ids;
	}
}
