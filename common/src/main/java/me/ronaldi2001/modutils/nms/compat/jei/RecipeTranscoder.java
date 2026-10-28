package me.ronaldi2001.modutils.nms.compat.jei;

import io.netty.buffer.Unpooled;
import me.ronaldi2001.modutils.nms.AdventureBridge;
import me.ronaldi2001.modutils.nms.Nms;
import me.ronaldi2001.modutils.nms.ItemTranslator;
import net.kyori.adventure.translation.GlobalTranslator;
import net.minecraft.core.HolderSet;
import me.ronaldi2001.modutils.nms.compat.polymer.PolymerHook;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.component.ItemLore;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Re-encodes a recipe from the server's (1.21.11) network layout into the layout of the client it is sent to.
 * <p>
 * The layouts were taken from the source of each version. What changed after 1.21.11:
 * <ul>
 *   <li>a leading {@code show_notification} flag on every recipe, the category now before the group, and
 *       stonecutting no longer has a group;</li>
 *   <li>recipe results are an {@code ItemStackTemplate} (item id, count, components) instead of an
 *       {@code ItemStack} (count, item id, components);</li>
 *   <li>transmute recipes gained a material count and a flag; smithing results use the new template.</li>
 * </ul>
 * Between 26.1, 26.2 and 26.3 the only difference is the transmute result, see {@link Layout}.
 * <p>
 * Item ids are remapped through ViaVersion and only the components that pick a custom model, name an item or give
 * it lore are kept (see {@link ItemTranslator}). Item tags are always expanded to explicit item lists, because a tag the client
 * doesn't have would throw while it decodes the packet. Anything that can't be represented makes the recipe return
 * null, so it is left out instead of corrupting the packet.
 * <p>
 * Results that are custom items are written as the configured display item with the custom item's model and name
 * (see {@link #resultFor}).
 */
final class RecipeTranscoder {
	/**
	 * The recipe layouts we can read and write: the one a server writes and the one a client expects.
	 */
	enum Layout {
		/**
		 * Minecraft 1.21.2 to 1.21.4: as the next, except that a transmute result is only an item, and the smithing
		 * recipes have all three ingredients optional and an item stack for a result.
		 */
		V1_21_3,
		/**
		 * Minecraft 1.21.5 to 1.21.11.
		 */
		V1_21_5,
		/**
		 * Minecraft 26.1 and 26.2.
		 */
		V26_1,
		/**
		 * Minecraft 26.3: like 26.1, but a transmute result may name no item (keep the input's).
		 */
		V26_3;

		boolean usesTemplates() {
			return this == V26_1 || this == V26_3;
		}

		/**
		 * @return the layout for a Minecraft version (given as its protocol number), or null if it isn't one we know
		 * the layout of. Versions are listed by hand because the layout has changed between them, and a version we
		 * haven't read the source of could have changed again.
		 */
		static @Nullable Layout forProtocol(int protocol) {
			return switch (protocol) {
				case 768, 769 -> V1_21_3;
				case 770, 771, 772, 773, 774 -> V1_21_5;
				case 775, 776 -> V26_1;
				case 777 -> V26_3;
				default -> null;
			};
		}
	}

	private static final int PROTOCOL_26_3 = 777;
	private static final Set<String> SUPPORTED_SERIALIZERS = Set.of(
			"minecraft:crafting_shaped", "minecraft:crafting_shapeless", "minecraft:crafting_transmute",
			"minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking",
			"minecraft:stonecutting", "minecraft:smithing_transform"
	);

	/** The first number of an ingredient in Polymer's extended layout (its IngredientExtension.MAGIC_NUMBER). */
	private static final int POLYMER_INGREDIENT_MARKER = -0x372ab82;

	/** The layout the server writes recipes in. */
	private final Layout source;
	/** The layout the client expects. */
	private final Layout layout;
	/** The recipe being written, for the debug log. */
	private volatile String currentRecipe = "?";
	/** For a client with Polymer: the recipe's ingredients as the server has them, in the order they are written. */
	private List<@Nullable Ingredient> exactIngredients = List.of();
	private int exactSlot;
	private final @Nullable PolymerHook polymer;
	/** Whether the client takes Polymer's extended ingredients (not every Polymer version has them). */
	private final boolean polymerIngredients;
	private final @Nullable ItemTranslator items;
	private final Locale locale;
	private final Item displayItem;

	/**
	 * @param source the layout of the server's own recipes
	 * @param layout the layout of the client's version
	 * @param items  null for a client on the server's own version: item ids are the same on both ends
	 * @param polymer for a client that knows the custom items (Polymer): marks recipe results, and ingredients too if
	 *                {@code polymerIngredients}
	 */
	RecipeTranscoder(Layout source, Layout layout, @Nullable ItemTranslator items, Locale locale,
					 RecipeDisplayConfig display, @Nullable PolymerHook polymer, boolean polymerIngredients) {
		this.source = source;
		this.polymerIngredients = polymerIngredients;
		this.layout = layout;
		this.polymer = polymer;
		this.items = items;
		this.locale = locale;
		this.displayItem = display.displayItem();
	}

	/**
	 * Item ids as the server knows them. Empty means the ingredient was empty.
	 */
	private record IdSet(List<Integer> ids) {
		boolean isEmpty() {
			return this.ids.isEmpty();
		}
	}

	/**
	 * @return whether the client is on the server's own version, so item ids and recipe layouts are the same
	 */
	boolean sameVersion() {
		return this.items == null;
	}

	/**
	 * @param serializerId the recipe serializer, e.g. {@code minecraft:crafting_shaped}
	 * @param serverBytes  the recipe as encoded by the server's own codec (without its id)
	 * @param custom       whether this recipe is a custom one (not in the minecraft namespace)
	 * @return the recipe in the client's layout, or null if it can't be represented
	 */
	byte @Nullable [] transcode(String serializerId, byte[] serverBytes, RegistryAccess registryAccess, boolean custom,
								Recipe<?> recipe, String recipeKey) {
		// A 26.x server writing for a client on its own version already has the client's layout and ids
		if (this.items == null && this.source.usesTemplates() && this.source == this.layout) {
			return this.native26(serializerId, serverBytes, registryAccess, custom, recipe, recipeKey);
		}
		this.currentRecipe = recipeKey;
		this.exactIngredients = this.polymer == null || !this.polymerIngredients
				? List.of() : this.polymer.ingredientsOf(recipe, serializerId);
		this.exactSlot = 0;
		RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(serverBytes), registryAccess);
		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
		try {
			boolean ok = switch (serializerId) {
				case "minecraft:crafting_shaped" -> this.shaped(in, out, custom);
				case "minecraft:crafting_shapeless" -> this.shapeless(in, out, custom);
				case "minecraft:crafting_transmute" -> this.transmute(in, out, custom);
				case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking" ->
						this.cooking(in, out, custom);
				case "minecraft:stonecutting" -> this.stonecutting(in, out, custom);
				case "minecraft:smithing_transform" -> this.smithingTransform(in, out, custom);
				default -> false;
			};
			if (!ok) return null;

			byte[] bytes = new byte[out.readableBytes()];
			out.readBytes(bytes);
			return bytes;
		} finally {
			in.release();
			out.release();
		}
	}

	/**
	 * A 26.x server writing for a client on its own version: the layout is already the client's, so everything is
	 * copied as it is except the result, which gets the same stand-in treatment as in {@link #resultFor}, and, for a
	 * client with Polymer, the ingredients that are custom items.
	 * Layouts are the 26.x ones documented on the methods above.
	 */
	private byte @Nullable [] native26(String serializerId, byte[] src, RegistryAccess registryAccess, boolean custom,
									   Recipe<?> recipe, String recipeKey) {
		this.currentRecipe = recipeKey;
		if (!SUPPORTED_SERIALIZERS.contains(serializerId)) return null;
		if (this.polymer == null && !custom) return src;

		List<@Nullable Ingredient> exact = this.polymer == null || !this.polymerIngredients
				? List.of() : this.polymer.ingredientsOf(recipe, serializerId);
		RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(src), registryAccess);
		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
		try {
			int slot = 0;
			switch (serializerId) {
				case "minecraft:crafting_shaped" -> {
					int start = in.readerIndex();
					in.readBoolean();
					in.readVarInt();
					in.readUtf();
					int width = in.readVarInt();
					int height = in.readVarInt();
					int count = in.readVarInt();
					out.writeBytes(src, start, in.readerIndex() - start);
					for (int i = 0; i < count; i++) this.copyIngredient(src, in, out, this.exactAt(exact, slot++), recipeKey);
					this.copyResult(src, in, out, custom);
				}
				case "minecraft:crafting_shapeless" -> {
					int start = in.readerIndex();
					in.readBoolean();
					in.readVarInt();
					in.readUtf();
					out.writeBytes(src, start, in.readerIndex() - start);
					this.copyResult(src, in, out, custom);
					int count = in.readVarInt();
					out.writeVarInt(count);
					for (int i = 0; i < count; i++) this.copyIngredient(src, in, out, this.exactAt(exact, slot++), recipeKey);
				}
				case "minecraft:crafting_transmute" -> {
					int start = in.readerIndex();
					in.readBoolean();
					in.readVarInt();
					in.readUtf();
					out.writeBytes(src, start, in.readerIndex() - start);
					this.copyIngredient(src, in, out, this.exactAt(exact, slot++), recipeKey);
					this.copyIngredient(src, in, out, this.exactAt(exact, slot++), recipeKey);
					// Material count bounds: a flags byte, then the min and max it says are there
					int boundsStart = in.readerIndex();
					int flags = in.readByte();
					if ((flags & 1) != 0) in.readInt();
					if ((flags & 2) != 0) in.readInt();
					out.writeBytes(src, boundsStart, in.readerIndex() - boundsStart);
					// 26.3 may name no result (keep the input's); earlier versions always have one
					boolean hasResult = true;
					if (SharedConstants.getProtocolVersion() >= PROTOCOL_26_3) {
						hasResult = in.readBoolean();
						out.writeBoolean(hasResult);
					}
					if (hasResult) this.copyResult(src, in, out, custom);
					// The flag that follows is copied with the rest below
				}
				case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking" -> {
					int start = in.readerIndex();
					in.readBoolean();
					in.readVarInt();
					in.readUtf();
					out.writeBytes(src, start, in.readerIndex() - start);
					this.copyIngredient(src, in, out, this.exactAt(exact, slot++), recipeKey);
					this.copyResult(src, in, out, custom);
				}
				case "minecraft:stonecutting" -> {
					int start = in.readerIndex();
					in.readBoolean();
					out.writeBytes(src, start, in.readerIndex() - start);
					this.copyIngredient(src, in, out, this.exactAt(exact, slot++), recipeKey);
					this.copyResult(src, in, out, custom);
				}
				default -> { // smithing_transform
					int start = in.readerIndex();
					in.readBoolean();
					out.writeBytes(src, start, in.readerIndex() - start);
					for (int i = 0; i < 3; i++) this.copyIngredient(src, in, out, this.exactAt(exact, slot++), recipeKey);
					this.copyResult(src, in, out, custom);
				}
			}

			out.writeBytes(src, in.readerIndex(), src.length - in.readerIndex());
			byte[] bytes = new byte[out.readableBytes()];
			out.readBytes(bytes);
			return bytes;
		} finally {
			in.release();
			out.release();
		}
	}

	private @Nullable Ingredient exactAt(List<@Nullable Ingredient> exact, int slot) {
		return slot < exact.size() ? exact.get(slot) : null;
	}

	/**
	 * Copies one ingredient as it is, or, for a client with Polymer and an ingredient that takes custom items, writes
	 * it in Polymer's extended layout: the marker, an empty list of plain items, then the Polymer ids.
	 */
	private void copyIngredient(byte[] src, RegistryFriendlyByteBuf in, RegistryFriendlyByteBuf out,
								@Nullable Ingredient exact, String recipeKey) {
		int start = in.readerIndex();
		this.readIngredient(in);
		int end = in.readerIndex();

		List<Integer> ids = exact == null || this.polymer == null ? null : this.polymer.polymerIds(exact, recipeKey);
		if (ids == null || ids.isEmpty()) {
			out.writeBytes(src, start, end - start);
			return;
		}
		out.writeVarInt(POLYMER_INGREDIENT_MARKER);
		out.writeVarInt(1); // no plain items
		out.writeVarInt(ids.size());
		ids.forEach(out::writeVarInt);
	}

	/** The result (item id, count, components) with the stand-in applied. */
	private void copyResult(byte[] src, RegistryFriendlyByteBuf in, RegistryFriendlyByteBuf out, boolean custom) {
		int start = in.readerIndex();
		ItemStack result = this.readItemResult(in);
		int end = in.readerIndex();

		// A client that knows the custom items shows the item itself, not the stand-in
		ItemStack marked = this.polymer == null ? result : this.polymer.markResult(result);
		ItemStack shown = marked != result ? marked : this.resultFor(result, custom);
		if (shown == result) {
			out.writeBytes(src, start, end - start);
		} else {
			out.writeVarInt(BuiltInRegistries.ITEM.getId(shown.getItem()));
			out.writeVarInt(shown.getCount());
			DataComponentPatch.STREAM_CODEC.encode(out, shown.getComponentsPatch());
		}
	}

	/**
	 * Items can't be added to JEI from the server, so a custom item can't be looked up by its own name. Instead its
	 * recipes all show the configured display item, wearing the custom item's model and name, which puts every
	 * custom result in one place.
	 * <p>
	 * Only results that are actually custom items get this: a custom recipe that gives a plain vanilla item (such as
	 * turning bricks back into brick items) keeps showing that item, since nothing new was added.
	 * <p>
	 * The model is the item's {@code item_model} if it has one, otherwise the model of its base item, with
	 * {@code custom_model_data} carried over so model definitions that depend on it still resolve.
	 *
	 * @return the stack to show for this result
	 */
	private ItemStack resultFor(ItemStack result, boolean custom) {
		// A plain vanilla item has no changed components at all
		boolean customItem = custom && !result.getComponentsPatch().isEmpty();
		if (!customItem) return result;

		// A client without the display item can't show the swap, so leave the result alone
		if (this.items != null && this.items.translateItemId(BuiltInRegistries.ITEM.getId(this.displayItem)) < 0) {
			return result;
		}

		Identifier model = result.getOrDefault(DataComponents.ITEM_MODEL, BuiltInRegistries.ITEM.getKey(result.getItem()));

		ItemStack shown = new ItemStack(this.displayItem, result.getCount());
		shown.set(DataComponents.ITEM_MODEL, model);

		CustomModelData modelData = result.get(DataComponents.CUSTOM_MODEL_DATA);
		if (modelData != null) shown.set(DataComponents.CUSTOM_MODEL_DATA, modelData);

		// The names are what tells the barriers apart in the list. Rendered here because Paper only does that for
		// items it sends itself, and the client has none of our translations.
		if (Nms.hasNonDefault(result, DataComponents.ITEM_NAME)) {
			shown.set(DataComponents.ITEM_NAME, this.render(result.get(DataComponents.ITEM_NAME)));
		}
		Component customName = result.get(DataComponents.CUSTOM_NAME);
		if (customName != null) shown.set(DataComponents.CUSTOM_NAME, this.render(customName));

		// The lore is what JEI shows under the name when hovering the result
		ItemLore lore = result.get(DataComponents.LORE);
		if (lore != null && !lore.lines().isEmpty()) {
			shown.set(DataComponents.LORE, new ItemLore(lore.lines().stream().map(this::render).toList()));
		}

		return shown;
	}

	private Component render(Component name) {
		return AdventureBridge.asVanilla(GlobalTranslator.render(AdventureBridge.asAdventure(name), this.locale));
	}

	// ------------------------------------------------------------------ recipes
	//
	// Read in the server's layout (this.source), written in the client's (this.layout). The layouts:
	//   shaped      1.21.11: group, category, width, height, ingredients, result stack, show notification
	//               26.x:    show notification, category, group, width, height, ingredients, result template
	//   shapeless   1.21.11: group, category, result stack, ingredients
	//               26.x:    show notification, category, group, result template, ingredients
	//   transmute   1.21.11: group, category, input, material, result (item id, count, components)
	//               26.1:    show notification, category, group, input, material, material count bounds, result
	//                        template, add material count to result
	//               26.3:    as 26.1, but the result is optional: present flag, then the template
	//   cooking     1.21.11: group, category, ingredient, result stack, experience, cooking time
	//               26.x:    show notification, category, group, ingredient, result template, experience, time
	//   stonecutting 1.21.11: group, ingredient, result stack;  26.x: show notification, ingredient, result template
	//   smithing    1.21.11: template, base, addition, result;  26.x: show notification first

	/**
	 * What the crafting recipes start with. The 1.21.11 layout has no show notification flag there.
	 */
	private record Header(boolean show, int category, String group) {
	}

	private Header readHeader(RegistryFriendlyByteBuf in) {
		if (this.source.usesTemplates()) {
			boolean show = in.readBoolean();
			int category = in.readVarInt();
			String group = in.readUtf();
			return new Header(show, category, group);
		}
		String group = in.readUtf();
		int category = in.readVarInt();
		return new Header(true, category, group);
	}

	private void writeHeader(RegistryFriendlyByteBuf out, Header header) {
		if (this.layout.usesTemplates()) {
			out.writeBoolean(header.show());
			out.writeVarInt(header.category());
			out.writeUtf(header.group());
		} else {
			out.writeUtf(header.group());
			out.writeVarInt(header.category());
		}
	}

	private boolean shaped(RegistryFriendlyByteBuf in, RegistryFriendlyByteBuf out, boolean custom) {
		Header header = this.readHeader(in);
		int width = in.readVarInt();
		int height = in.readVarInt();
		int count = in.readVarInt();
		List<IdSet> slots = new ArrayList<>(count);
		for (int i = 0; i < count; i++) slots.add(this.readIngredient(in));
		ItemStack result = this.shownResult(this.readResult(in), custom);
		// In the 1.21.11 layout the flag comes last
		boolean show = this.source.usesTemplates() ? header.show() : in.readBoolean();

		this.writeHeader(out, new Header(show, header.category(), header.group()));
		out.writeVarInt(width);
		out.writeVarInt(height);
		out.writeVarInt(count);
		for (IdSet slot : slots) {
			if (!this.writeIngredient(out, slot, true)) return false;
		}
		if (!this.writeResult(out, result)) return false;
		if (!this.layout.usesTemplates()) out.writeBoolean(show);
		return true;
	}

	private boolean shapeless(RegistryFriendlyByteBuf in, RegistryFriendlyByteBuf out, boolean custom) {
		Header header = this.readHeader(in);
		ItemStack result = this.shownResult(this.readResult(in), custom);
		int count = in.readVarInt();
		List<IdSet> ingredients = new ArrayList<>(count);
		for (int i = 0; i < count; i++) ingredients.add(this.readIngredient(in));

		this.writeHeader(out, header);
		if (!this.writeResult(out, result)) return false;
		out.writeVarInt(count);
		for (IdSet ingredient : ingredients) {
			if (!this.writeIngredient(out, ingredient, false)) return false;
		}
		return true;
	}

	private boolean transmute(RegistryFriendlyByteBuf in, RegistryFriendlyByteBuf out, boolean custom) {
		Header header = this.readHeader(in);
		IdSet input = this.readIngredient(in);
		IdSet material = this.readIngredient(in);

		boolean hasResult = true;
		ItemStack result = null;
		if (this.source.usesTemplates()) {
			int flags = in.readByte(); // material count bounds: which of min and max follow
			if ((flags & 1) != 0) in.readInt();
			if ((flags & 2) != 0) in.readInt();
			if (this.source == Layout.V26_3) hasResult = in.readBoolean();
			if (hasResult) result = this.shownResult(this.readItemResult(in), custom);
			in.readBoolean(); // add the material count to the result
		} else {
			result = this.shownResult(this.readTransmuteResult(in), custom);
		}
		// Only 26.3 can say "the result is the input's item"
		if (!hasResult && this.layout != Layout.V26_3) return false;

		this.writeHeader(out, header);
		if (!this.writeIngredient(out, input, false)) return false;
		if (!this.writeIngredient(out, material, false)) return false;
		if (this.layout.usesTemplates()) {
			out.writeByte(3); // bounds: has min and max
			out.writeInt(1);
			out.writeInt(1);
			if (this.layout == Layout.V26_3) out.writeBoolean(hasResult);
		}
		if (hasResult && !this.writeTransmuteResult(out, result)) return false;
		if (this.layout.usesTemplates()) out.writeBoolean(false);
		return true;
	}

	private boolean cooking(RegistryFriendlyByteBuf in, RegistryFriendlyByteBuf out, boolean custom) {
		Header header = this.readHeader(in);
		IdSet ingredient = this.readIngredient(in);
		ItemStack result = this.shownResult(this.readResult(in), custom);
		float experience = in.readFloat();
		int cookingTime = in.readInt();

		this.writeHeader(out, header);
		if (!this.writeIngredient(out, ingredient, false)) return false;
		if (!this.writeResult(out, result)) return false;
		out.writeFloat(experience);
		out.writeInt(cookingTime);
		return true;
	}

	private boolean stonecutting(RegistryFriendlyByteBuf in, RegistryFriendlyByteBuf out, boolean custom) {
		boolean show = true;
		String group = "";
		if (this.source.usesTemplates()) {
			show = in.readBoolean();
		} else {
			group = in.readUtf();
		}
		IdSet ingredient = this.readIngredient(in);
		ItemStack result = this.shownResult(this.readResult(in), custom);

		if (this.layout.usesTemplates()) {
			out.writeBoolean(show);
		} else {
			out.writeUtf(group);
		}
		if (!this.writeIngredient(out, ingredient, false)) return false;
		return this.writeResult(out, result);
	}

	private boolean smithingTransform(RegistryFriendlyByteBuf in, RegistryFriendlyByteBuf out, boolean custom) {
		boolean show = !this.source.usesTemplates() || in.readBoolean();
		IdSet template = this.readIngredient(in);
		IdSet base = this.readIngredient(in);
		IdSet addition = this.readIngredient(in);
		ItemStack result = this.shownResult(this.readSmithingResult(in), custom);

		if (this.layout.usesTemplates()) out.writeBoolean(show);
		if (!this.writeIngredient(out, template, true)) return false;
		if (!this.writeIngredient(out, base, this.layout == Layout.V1_21_3)) return false;
		if (!this.writeIngredient(out, addition, true)) return false;
		return this.writeSmithingResult(out, result);
	}

	// ------------------------------------------------------------------ pieces

	/**
	 * The result as the client is shown it: marked as the custom item it is for a client that knows the custom items
	 * (Polymer), otherwise with the stand-in of {@link #resultFor}.
	 */
	private ItemStack shownResult(ItemStack result, boolean custom) {
		ItemStack marked = this.polymer == null ? result : this.polymer.markResult(result);
		return marked != result ? marked : this.resultFor(result, custom);
	}

	/**
	 * A recipe result that is a whole stack: an {@code ItemStack} (count, item id, components) in the 1.21.11 layout, a
	 * template (item id, count, components) in the newer ones.
	 */
	private ItemStack readResult(RegistryFriendlyByteBuf in) {
		return this.source.usesTemplates() ? this.readItemResult(in) : ItemStack.STREAM_CODEC.decode(in);
	}

	/**
	 * @return false if the client has no equivalent of the item
	 */
	private boolean writeResult(RegistryFriendlyByteBuf out, ItemStack result) {
		if (this.layout.usesTemplates()) return this.writeItemResult(out, result);

		if (this.items == null) {
			ItemStack.STREAM_CODEC.encode(out, result);
			return true;
		}
		if (result.isEmpty() || this.items.translateItemId(BuiltInRegistries.ITEM.getId(result.getItem())) < 0) return false;
		this.items.writeStack(out, result);
		return true;
	}

	/**
	 * A result written as item id, count and components: transmute and smithing results, and every result in the newer
	 * layouts.
	 */
	private boolean writeItemResult(RegistryFriendlyByteBuf out, ItemStack result) {
		if (this.items == null) {
			out.writeVarInt(BuiltInRegistries.ITEM.getId(result.getItem()));
			out.writeVarInt(result.getCount());
			DataComponentPatch.STREAM_CODEC.encode(out, result.getComponentsPatch());
			return true;
		}
		return this.items.writeTemplate(out, result);
	}

	/**
	 * A transmute result: only the item before 1.21.5, item id, count and components from then on.
	 */
	private ItemStack readTransmuteResult(RegistryFriendlyByteBuf in) {
		if (this.source != Layout.V1_21_3) return this.readItemResult(in);

		Item item = BuiltInRegistries.ITEM.byId(in.readVarInt());
		return new ItemStack(BuiltInRegistries.ITEM.wrapAsHolder(item), 1);
	}

	private boolean writeTransmuteResult(RegistryFriendlyByteBuf out, ItemStack result) {
		if (this.layout != Layout.V1_21_3) return this.writeItemResult(out, result);

		int serverId = BuiltInRegistries.ITEM.getId(result.getItem());
		int clientId = this.items == null ? serverId : this.items.translateItemId(serverId);
		if (clientId < 0) return false;
		out.writeVarInt(clientId);
		return true;
	}

	/**
	 * A smithing result: an item stack before 1.21.5, item id, count and components from then on.
	 */
	private ItemStack readSmithingResult(RegistryFriendlyByteBuf in) {
		return this.source == Layout.V1_21_3 ? ItemStack.STREAM_CODEC.decode(in) : this.readItemResult(in);
	}

	private boolean writeSmithingResult(RegistryFriendlyByteBuf out, ItemStack result) {
		return this.layout == Layout.V1_21_3 ? this.writeResult(out, result) : this.writeItemResult(out, result);
	}

	/**
	 * A result as item id, count, components: the {@code TransmuteResult} of 1.21.5 and newer, and the newer templates.
	 */
	private ItemStack readItemResult(RegistryFriendlyByteBuf in) {
		int itemId = in.readVarInt();
		int count = in.readVarInt();
		DataComponentPatch patch = DataComponentPatch.STREAM_CODEC.decode(in);
		Item item = BuiltInRegistries.ITEM.byId(itemId);
		return new ItemStack(BuiltInRegistries.ITEM.wrapAsHolder(item), count, patch);
	}

	/**
	 * A holder set of items: {@code 0 + tag id}, or {@code size + 1} followed by the item ids.
	 */
	private IdSet readIngredient(RegistryFriendlyByteBuf in) {
		int header = in.readVarInt();
		List<Integer> ids = new ArrayList<>();
		if (header == 0) {
			// Expand the tag here: a tag the client doesn't have would throw while it decodes the packet
			ids.addAll(Nms.tagItemIds(Identifier.STREAM_CODEC.decode(in)));
		} else {
			for (int i = 0; i < header - 1; i++) ids.add(in.readVarInt());
		}
		return new IdSet(ids);
	}

	/**
	 * Writes the ingredient with its items translated for the client.
	 *
	 * @param optional an empty ingredient is allowed (written as an empty set)
	 * @return false if the ingredient has items on the server but none exist on the client
	 */
	private boolean writeIngredient(RegistryFriendlyByteBuf out, IdSet ingredient, boolean optional) {
		// For a client with Polymer, an ingredient that takes custom items names them by Polymer's ids
		Ingredient exact = this.exactAt(this.exactIngredients, this.exactSlot++);
		List<Integer> polymerIds = exact == null || this.polymer == null ? null : this.polymer.polymerIds(exact, this.currentRecipe);
		if (polymerIds != null && !polymerIds.isEmpty()) {
			out.writeVarInt(POLYMER_INGREDIENT_MARKER);
			out.writeVarInt(1); // no plain items
			out.writeVarInt(polymerIds.size());
			polymerIds.forEach(out::writeVarInt);
			return true;
		}

		List<Integer> translated = new ArrayList<>();
		for (int serverId : ingredient.ids()) {
			int clientId = this.items == null ? serverId : this.items.translateItemId(serverId);
			if (clientId >= 0) translated.add(clientId);
		}

		if (translated.isEmpty() && (!optional || !ingredient.isEmpty())) return false;

		out.writeVarInt(translated.size() + 1);
		translated.forEach(out::writeVarInt);
		return true;
	}
}
