package me.ronaldi2001.modutils.nms.compat.jei;

import io.netty.buffer.Unpooled;
import me.ronaldi2001.modutils.nms.ItemTranslator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Brewing recipes for 26.3 clients on servers that don't have them. From 26.3 brewing is a recipe like the others, one per
 * container, potion and ingredient, which JEI lists from the recipes it is sent. Older servers only have their potion mixes
 * ({@link BrewingMixes}), so the recipes are written from those, in the layout of 26.3's {@code BrewingRecipe}: the input
 * and the ingredient (items, and for the input the potions it may hold), then the result with its potion.
 * <p>
 * Registry ids are the client's own, so potions and the potion component are numbered as 26.3 numbers them (read from its
 * registry report); items are translated with the player's {@link ItemTranslator}.
 */
final class BrewingRecipes {
	/**
	 * The potions of 26.3 in registry order: the position is the id the client knows it by.
	 */
	private static final List<String> POTIONS_26_3 = List.of(
			"water", "mundane", "thick", "awkward", "night_vision", "long_night_vision", "invisibility",
			"long_invisibility", "leaping", "long_leaping", "strong_leaping", "fire_resistance", "long_fire_resistance",
			"swiftness", "long_swiftness", "strong_swiftness", "slowness", "long_slowness", "strong_slowness",
			"turtle_master", "long_turtle_master", "strong_turtle_master", "water_breathing", "long_water_breathing",
			"healing", "strong_healing", "harming", "strong_harming", "poison", "long_poison", "strong_poison",
			"regeneration", "long_regeneration", "strong_regeneration", "strength", "long_strength", "strong_strength",
			"weakness", "long_weakness", "luck", "slow_falling", "long_slow_falling", "wind_charged", "weaving", "oozing",
			"infested"
	);
	/**
	 * The id of the potion contents component in 26.3.
	 */
	private static final int POTION_CONTENTS_COMPONENT_26_3 = 53;

	private BrewingRecipes() {
	}

	/**
	 * One brewing step the server knows: a potion in a container, brewed with an ingredient into another potion.
	 *
	 * @param container    the potion, splash potion or lingering potion item the potion is in
	 * @param inputPotion  the potion's registry name, such as {@code minecraft:awkward}
	 * @param output       the item that comes out (a different container for gunpowder and dragon's breath)
	 * @param outputPotion the registry name of the potion that comes out
	 */
	record Mix(Item container, String inputPotion, Item reagent, Item output, String outputPotion) {
	}

	/**
	 * A recipe ready to send.
	 *
	 * @param path the recipe's id without a namespace, such as {@code brewing/potion_awkward_blaze_powder}
	 * @param body what follows the id
	 */
	record Encoded(String path, byte[] body) {
	}

	/**
	 * @return the mixes as recipes for a 26.3 client, without those the client has no items or potions for
	 */
	static List<Encoded> encode(List<Mix> mixes, ItemTranslator items) {
		List<Encoded> recipes = new ArrayList<>(mixes.size());
		for (Mix mix : mixes) {
			int inputPotion = POTIONS_26_3.indexOf(name(mix.inputPotion()));
			int outputPotion = POTIONS_26_3.indexOf(name(mix.outputPotion()));
			int container = items.translateItemId(BuiltInRegistries.ITEM.getId(mix.container()));
			int reagent = items.translateItemId(BuiltInRegistries.ITEM.getId(mix.reagent()));
			int output = items.translateItemId(BuiltInRegistries.ITEM.getId(mix.output()));
			if (inputPotion < 0 || outputPotion < 0 || container < 0 || reagent < 0 || output < 0) continue;

			FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
			try {
				// The input: its item, and the one potion it holds
				itemSet(out, container);
				out.writeBoolean(true);
				out.writeBoolean(true); // the potions it may hold ...
				out.writeVarInt(2); // ... a set of one
				out.writeVarInt(inputPotion);
				out.writeBoolean(false); // no effects to match
				// The ingredient: any of that item
				itemSet(out, reagent);
				out.writeBoolean(false);
				// The result: the item, one of it, and the potion it holds
				out.writeVarInt(output);
				out.writeVarInt(1);
				out.writeVarInt(1); // one component set ...
				out.writeVarInt(0); // ... none removed
				out.writeVarInt(POTION_CONTENTS_COMPONENT_26_3);
				out.writeBoolean(true);
				out.writeVarInt(outputPotion);
				out.writeBoolean(false); // no custom color
				out.writeVarInt(0); // no custom effects
				out.writeBoolean(false); // no custom name
				byte[] body = new byte[out.readableBytes()];
				out.readBytes(body);
				recipes.add(new Encoded(path(mix), body));
			} finally {
				out.release();
			}
		}
		return recipes;
	}

	/**
	 * A holder set of one item.
	 */
	private static void itemSet(FriendlyByteBuf out, int item) {
		out.writeVarInt(2);
		out.writeVarInt(item);
	}

	private static String path(Mix mix) {
		return "brewing/" + pathOf(mix.container()) + "_" + name(mix.inputPotion()) + "_" + pathOf(mix.reagent());
	}

	private static String pathOf(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath();
	}

	/**
	 * @return a registry name without its namespace; only vanilla potions are numbered
	 */
	private static String name(String registryName) {
		return registryName.startsWith("minecraft:") ? registryName.substring("minecraft:".length()) : registryName;
	}
}
