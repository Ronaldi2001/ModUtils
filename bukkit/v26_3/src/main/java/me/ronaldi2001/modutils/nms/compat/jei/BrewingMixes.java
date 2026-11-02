package me.ronaldi2001.modutils.nms.compat.jei;

import java.util.List;

/**
 * 26.3 has brewing as recipes of its own, which are synced like the others, so there are no potion mixes to turn into
 * recipes. The shared copy is for the versions before it.
 */
final class BrewingMixes {
	private BrewingMixes() {
	}

	static List<BrewingRecipes.Mix> collect() {
		return List.of();
	}
}
