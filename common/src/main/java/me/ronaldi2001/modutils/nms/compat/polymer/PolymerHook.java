package me.ronaldi2001.modutils.nms.compat.polymer;

import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * What the JEI recipe sync needs from the Polymer support (see the adapters that provide one). Players whose client has
 * the Polymer mod can be told which custom item an ingredient is, instead of the plain item it is made of.
 * <p>
 * Adapters without Polymer support use {@link #NONE}, which never does anything.
 */
public interface PolymerHook {
	PolymerHook NONE = new PolymerHook() {
	};

	/**
	 * @return whether this player has started the Polymer handshake but not finished it, so their recipes should wait
	 */
	default boolean pending(UUID player) {
		return false;
	}

	/**
	 * @return whether this player's client has been told about the custom items, so recipe results can be marked as the
	 * custom item they are
	 */
	default boolean knowsCustomItems(UUID player) {
		return false;
	}

	/**
	 * @return whether this player's client understands the extended recipe ingredients
	 */
	default boolean extendedIngredients(UUID player) {
		return false;
	}

	/**
	 * @param recipe     the recipe
	 * @param serializer the recipe's serializer id, e.g. {@code minecraft:crafting_shaped}
	 * @return the recipe's ingredients in the order they are written in the packet, with null where there is none
	 */
	default List<@Nullable Ingredient> ingredientsOf(Recipe<?> recipe, String serializer) {
		return List.of();
	}

	/**
	 * @param recipeKey the recipe the ingredient belongs to, e.g. {@code rawhub:backpack_upgrade_totem}
	 * @return the Polymer ids of the custom items this ingredient takes, or null if it takes plain items
	 */
	default @Nullable List<Integer> polymerIds(Ingredient ingredient, String recipeKey) {
		return null;
	}

	/**
	 * @return the stack marked as the custom item it is, for a client that knows the custom items, or the same stack if
	 * it is not one
	 */
	default net.minecraft.world.item.ItemStack markResult(net.minecraft.world.item.ItemStack stack) {
		return stack;
	}

	/**
	 * Makes sure the client knows every custom item the recipes mention. Must be called before the recipes are sent.
	 */
	default void ensureSynced(Player player) {
	}

	/**
	 * Called when a player's Polymer handshake finishes, so their recipes can be sent with the extra information.
	 */
	default void onReady(Consumer<Player> callback) {
	}

	/**
	 * Stops a callback given to {@link #onReady} from being called again.
	 */
	default void removeReady(Consumer<Player> callback) {
	}
}
