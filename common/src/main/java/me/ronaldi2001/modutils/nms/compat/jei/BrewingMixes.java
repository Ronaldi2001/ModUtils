package me.ronaldi2001.modutils.nms.compat.jei;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The brewing the server knows, for versions before 26.3. The server only has the mixes in its {@link PotionBrewing}, with
 * no way to list them, so each potion in each container is brewed with each ingredient to see what comes out: what changes
 * is a mix. That also covers mixes other plugins add.
 * <p>
 * 26.3 has no PotionBrewing (brewing is recipes there), so that version has its own copy of this file with nothing in it.
 */
final class BrewingMixes {
	private BrewingMixes() {
	}

	static List<BrewingRecipes.Mix> collect() {
		PotionBrewing brewing = MinecraftServer.getServer().potionBrewing();

		List<Item> reagents = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (item != Items.AIR && brewing.isIngredient(new ItemStack(item))) reagents.add(item);
		}

		List<BrewingRecipes.Mix> mixes = new ArrayList<>();
		for (Item container : new Item[]{Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION}) {
			for (Potion potion : BuiltInRegistries.POTION) {
				ItemStack input = PotionContents.createItemStack(container, BuiltInRegistries.POTION.wrapAsHolder(potion));
				for (Item reagent : reagents) {
					ItemStack result = brewing.mix(new ItemStack(reagent), input);
					if (result == input || result.isEmpty() || result.getCount() != 1) continue;

					PotionContents contents = result.get(DataComponents.POTION_CONTENTS);
					Optional<Holder<Potion>> brewed = contents == null ? Optional.empty() : contents.potion();
					if (brewed.isEmpty()) continue;
					if (result.getItem() == container && brewed.get().value() == potion) continue;

					mixes.add(new BrewingRecipes.Mix(
							container, String.valueOf(BuiltInRegistries.POTION.getKey(potion)), reagent, result.getItem(),
							String.valueOf(BuiltInRegistries.POTION.getKey(brewed.get().value()))
					));
				}
			}
		}
		return mixes;
	}
}
