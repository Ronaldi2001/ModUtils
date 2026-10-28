package me.ronaldi2001.modutils.nms.compat.jei;

import me.ronaldi2001.modutils.nms.VersionShim;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Server half of JEI's "move recipe ingredients into the crafting grid" feature.
 * <p>
 * The transfer algorithm is ported from JEI's {@code BasicRecipeTransferHandlerServer} (MIT license,
 * Copyright (c) 2014-2015 mezz), so a transfer behaves exactly like it does on a JEI server.
 * <p>
 * JEI only checks slot rules, so on its own a modified client could aim a transfer at any open menu, including this
 * plugin's GUIs (shops, backpacks, ...). {@link #validate} therefore only allows real crafting menus and requires the
 * source slots to belong to the player's own inventory.
 */
final class RecipeTransfer {
	/** One planned move: take {@code count} of the stack in {@code inventorySlot} into {@code craftingSlot}. */
	record Operation(int inventorySlotId, int craftingSlotId, int count) {
	}

	/** Upper bound on any list in a request, well above what a 3x3 grid plus an inventory needs. */
	static final int MAX_LIST_SIZE = 128;

	private record RequiredTransfer(Slot recipeSlot, Slot hint, ItemStack stack) {
	}

	private final Logger logger;

	RecipeTransfer(Logger logger) {
		this.logger = logger;
	}

	/**
	 * @return whether the transfer was applied
	 */
	boolean transfer(
			ServerPlayer player,
			List<Operation> operations,
			List<Integer> craftingSlotIds,
			List<Integer> inventorySlotIds,
			boolean maxTransfer,
			boolean requireCompleteSets
	) {
		AbstractContainerMenu menu = player.containerMenu;
		List<Slot> craftingSlots = this.getSlots(menu, craftingSlotIds);
		List<Slot> inventorySlots = this.getSlots(menu, inventorySlotIds);
		if (craftingSlots == null || inventorySlots == null) return false;

		if (!this.validate(player, menu, operations, craftingSlots, inventorySlots)) return false;
		if (!this.canClearCraftingSlots(player, craftingSlots)) return false;

		List<RequiredTransfer> requiredTransfers = this.calculateRequiredTransfers(operations, player);
		if (requiredTransfers == null) return false;

		// Transfer as many items as possible only if the player asked for it and the recipe allows partial sets
		boolean transferAsCompleteSets = requireCompleteSets || !maxTransfer;

		Map<Slot, ItemStack> recipeSlotToTakenStacks = this.takeItemsFromInventory(
				player, requiredTransfers, craftingSlots, inventorySlots, transferAsCompleteSets, maxTransfer
		);
		if (recipeSlotToTakenStacks.isEmpty()) return false;

		List<ItemStack> clearedCraftingItems = this.clearCraftingGrid(craftingSlots, player);
		List<ItemStack> remainderItems = this.putItemsIntoCraftingGrid(recipeSlotToTakenStacks, requireCompleteSets);

		this.stowItems(player, inventorySlots, clearedCraftingItems);
		this.stowItems(player, inventorySlots, remainderItems);

		menu.broadcastChanges();
		return true;
	}

	private List<Slot> getSlots(AbstractContainerMenu menu, List<Integer> slotIds) {
		if (slotIds.size() > menu.slots.size()) {
			this.logger.warning("JEI transfer has too many slot ids (" + slotIds.size() + ") for " + menu.getClass().getSimpleName());
			return null;
		}
		List<Slot> slots = new ArrayList<>(slotIds.size());
		for (int slotId : slotIds) {
			if (slotId < 0 || slotId >= menu.slots.size()) {
				this.logger.warning("JEI transfer has invalid slot id " + slotId + " for " + menu.getClass().getSimpleName());
				return null;
			}
			slots.add(menu.getSlot(slotId));
		}
		return slots;
	}

	private boolean validate(
			ServerPlayer player,
			AbstractContainerMenu menu,
			Collection<Operation> operations,
			Collection<Slot> craftingSlots,
			Collection<Slot> inventorySlots
	) {
		// Only vanilla crafting-style menus: never plugin GUIs, chests, shops, etc.
		if (!(menu instanceof CraftingMenu || menu instanceof InventoryMenu || menu instanceof AbstractFurnaceMenu)) {
			return false;
		}

		for (Slot slot : inventorySlots) {
			if (slot.container != player.getInventory()) return false;
		}
		for (Slot slot : craftingSlots) {
			if (slot.container == player.getInventory()) return false;
		}

		Set<Integer> inventoryIds = new HashSet<>();
		inventorySlots.forEach(s -> inventoryIds.add(s.index));
		Set<Integer> craftingIds = new HashSet<>();
		craftingSlots.forEach(s -> craftingIds.add(s.index));

		// Crafting and inventory slots must be disjoint real slots (not recipe outputs)
		for (Integer id : inventoryIds) {
			if (craftingIds.contains(id)) return false;
		}
		for (Slot slot : craftingSlots) {
			if (slot.isFake()) return false;
		}
		for (Slot slot : inventorySlots) {
			if (slot.isFake()) return false;
		}

		for (Operation operation : operations) {
			if (operation.inventorySlotId() < 0 || operation.inventorySlotId() >= menu.slots.size()) return false;
			if (operation.craftingSlotId() < 0 || operation.craftingSlotId() >= menu.slots.size()) return false;
			// Every target must be a listed crafting slot, every source a listed inventory or crafting slot
			if (!craftingIds.contains(operation.craftingSlotId())) return false;
			if (!inventoryIds.contains(operation.inventorySlotId()) && !craftingIds.contains(operation.inventorySlotId())) return false;
		}
		return true;
	}

	private boolean canClearCraftingSlots(Player player, List<Slot> craftingSlots) {
		for (Slot craftingSlot : craftingSlots) {
			ItemStack stack = craftingSlot.getItem();
			if (!stack.isEmpty() && (!craftingSlot.mayPickup(player) || !craftingSlot.mayPlace(stack))) {
				return false;
			}
		}
		return true;
	}

	private int getSlotStackLimit(Map<Slot, ItemStack> recipeSlotToTakenStacks, boolean requireCompleteSets) {
		if (!requireCompleteSets) return Integer.MAX_VALUE;

		return recipeSlotToTakenStacks.entrySet().stream()
				.mapToInt(e -> {
					Slot craftingSlot = e.getKey();
					ItemStack transferItem = e.getValue();
					if (craftingSlot.mayPlace(transferItem)) {
						return craftingSlot.getMaxStackSize(transferItem);
					}
					return Integer.MAX_VALUE;
				})
				.min()
				.orElse(Integer.MAX_VALUE);
	}

	private List<ItemStack> clearCraftingGrid(List<Slot> craftingSlots, Player player) {
		List<ItemStack> clearedCraftingItems = new ArrayList<>();
		for (Slot craftingSlot : craftingSlots) {
			if (!craftingSlot.mayPickup(player)) continue;

			ItemStack item = craftingSlot.getItem();
			if (!item.isEmpty() && craftingSlot.mayPlace(item)) {
				clearedCraftingItems.add(craftingSlot.safeTake(Integer.MAX_VALUE, Integer.MAX_VALUE, player));
			}
		}
		return clearedCraftingItems;
	}

	private List<ItemStack> putItemsIntoCraftingGrid(Map<Slot, ItemStack> recipeSlotToTakenStacks, boolean requireCompleteSets) {
		final int slotStackLimit = this.getSlotStackLimit(recipeSlotToTakenStacks, requireCompleteSets);
		List<ItemStack> remainderItems = new ArrayList<>();

		recipeSlotToTakenStacks.forEach((slot, stack) -> {
			ItemStack remainder = slot.safeInsert(stack, slotStackLimit);
			if (!remainder.isEmpty()) remainderItems.add(remainder);
		});
		return remainderItems;
	}

	private List<RequiredTransfer> calculateRequiredTransfers(List<Operation> operations, Player player) {
		List<RequiredTransfer> requiredTransfers = new ArrayList<>(operations.size());
		Map<Slot, ItemStack> targetSlotStacks = new HashMap<>();
		for (Operation operation : operations) {
			Slot recipeSlot = player.containerMenu.getSlot(operation.craftingSlotId());
			Slot inventorySlot = player.containerMenu.getSlot(operation.inventorySlotId());
			if (!inventorySlot.allowModification(player)) return null;

			ItemStack slotStack = inventorySlot.getItem();
			if (slotStack.isEmpty()) return null;

			ItemStack stack = slotStack.copy();
			stack.setCount(operation.count());
			if (!recipeSlot.mayPlace(stack)) return null;

			ItemStack targetSlotStack = targetSlotStacks.putIfAbsent(recipeSlot, stack);
			if (targetSlotStack != null && !ItemStack.isSameItemSameComponents(targetSlotStack, stack)) {
				// Different ingredients can't share one crafting slot
				return null;
			}
			requiredTransfers.add(new RequiredTransfer(recipeSlot, inventorySlot, stack));
		}
		return requiredTransfers;
	}

	private Map<Slot, ItemStack> takeItemsFromInventory(
			Player player,
			List<RequiredTransfer> requiredTransfers,
			List<Slot> craftingSlots,
			List<Slot> inventorySlots,
			boolean transferAsCompleteSets,
			boolean maxTransfer
	) {
		if (!maxTransfer) {
			return this.removeOneSetOfItemsFromInventory(player, requiredTransfers, craftingSlots, inventorySlots, transferAsCompleteSets);
		}

		List<RequiredTransfer> remainingRequiredTransfers = new ArrayList<>(requiredTransfers);
		Map<Slot, ItemStack> recipeSlotToResult = new HashMap<>(requiredTransfers.size());
		while (true) {
			this.removeFullRecipeSlots(remainingRequiredTransfers, recipeSlotToResult);
			if (remainingRequiredTransfers.isEmpty()) break;

			Map<Slot, ItemStack> foundItemsInSet = this.removeOneSetOfItemsFromInventory(
					player, remainingRequiredTransfers, craftingSlots, inventorySlots, transferAsCompleteSets
			);
			if (foundItemsInSet.isEmpty()) break;

			foundItemsInSet.forEach((slot, stack) -> this.merge(recipeSlotToResult, slot, stack));
		}
		return recipeSlotToResult;
	}

	private void removeFullRecipeSlots(List<RequiredTransfer> requiredTransfers, Map<Slot, ItemStack> recipeSlotToResult) {
		Set<Slot> fullRecipeSlots = new HashSet<>();
		for (RequiredTransfer requiredTransfer : requiredTransfers) {
			Slot recipeSlot = requiredTransfer.recipeSlot();
			ItemStack resultStack = recipeSlotToResult.get(recipeSlot);
			if (resultStack == null) continue;

			int requiredCount = requiredTransfers.stream()
					.filter(t -> t.recipeSlot() == recipeSlot)
					.mapToInt(t -> t.stack().getCount())
					.sum();
			int maxStackSize = Integer.MAX_VALUE;
			if (recipeSlot.mayPlace(resultStack)) {
				maxStackSize = recipeSlot.getMaxStackSize(resultStack);
			}
			if (resultStack.getCount() + requiredCount > maxStackSize) {
				fullRecipeSlots.add(recipeSlot);
			}
		}
		requiredTransfers.removeIf(t -> fullRecipeSlots.contains(t.recipeSlot()));
	}

	private Map<Slot, ItemStack> removeOneSetOfItemsFromInventory(
			Player player,
			List<RequiredTransfer> requiredTransfers,
			List<Slot> craftingSlots,
			List<Slot> inventorySlots,
			boolean transferAsCompleteSets
	) {
		// Only needed to roll back when a complete set can't be found
		Map<Slot, ItemStack> originalSlotContents = transferAsCompleteSets ? new HashMap<>() : null;
		Map<Slot, ItemStack> foundItemsInSet = new HashMap<>(requiredTransfers.size());

		for (RequiredTransfer requiredTransfer : requiredTransfers) {
			Slot recipeSlot = requiredTransfer.recipeSlot();
			ItemStack requiredStack = requiredTransfer.stack();

			Slot sourceSlot = this.getSlotWithStack(player, requiredStack, craftingSlots, inventorySlots, requiredTransfer.hint()).orElse(null);
			if (sourceSlot != null) {
				if (originalSlotContents != null && !originalSlotContents.containsKey(sourceSlot)) {
					originalSlotContents.put(sourceSlot, sourceSlot.getItem().copy());
				}
				ItemStack removed = sourceSlot.safeTake(requiredStack.getCount(), Integer.MAX_VALUE, player);
				this.merge(foundItemsInSet, recipeSlot, removed);
			} else if (transferAsCompleteSets) {
				// The full set wasn't satisfied: undo everything taken during this iteration
				originalSlotContents.forEach(Slot::set);
				return Map.of();
			}
		}
		return foundItemsInSet;
	}

	private void merge(Map<Slot, ItemStack> result, Slot slot, ItemStack itemStack) {
		ItemStack existing = result.get(slot);
		if (existing == null) {
			result.put(slot, itemStack);
		} else {
			existing.grow(itemStack.getCount());
		}
	}

	private Optional<Slot> getSlotWithStack(Player player, ItemStack stack, List<Slot> craftingSlots, List<Slot> inventorySlots, Slot hint) {
		if (this.isValidAndMatches(player, hint, stack)) return Optional.of(hint);

		return craftingSlots.stream().filter(slot -> this.isValidAndMatches(player, slot, stack)).findFirst()
				.or(() -> inventorySlots.stream().filter(slot -> this.isValidAndMatches(player, slot, stack)).findFirst());
	}

	private boolean isValidAndMatches(Player player, Slot slot, ItemStack stack) {
		ItemStack contained = slot.getItem();
		return ItemStack.isSameItemSameComponents(stack, contained)
				&& contained.getCount() >= stack.getCount()
				&& slot.allowModification(player);
	}

	private void stowItems(Player player, List<Slot> inventorySlots, List<ItemStack> itemStacks) {
		for (ItemStack itemStack : itemStacks) {
			ItemStack remainder = this.stowItem(player, inventorySlots, itemStack);
			if (!remainder.isEmpty() && !player.getInventory().add(remainder)) {
				VersionShim.dropItem(player, remainder);
			}
		}
	}

	private ItemStack stowItem(Player player, Collection<Slot> slots, ItemStack stack) {
		if (stack.isEmpty()) return ItemStack.EMPTY;

		ItemStack remainder = stack.copy();

		// Add to existing stacks first
		for (Slot slot : slots) {
			if (!slot.mayPickup(player)) continue;

			ItemStack inventoryStack = slot.getItem();
			if (!inventoryStack.isEmpty() && inventoryStack.isStackable()) {
				remainder = slot.safeInsert(remainder);
				if (remainder.isEmpty()) return ItemStack.EMPTY;
			}
		}

		// Then empty slots
		for (Slot slot : slots) {
			if (slot.getItem().isEmpty()) {
				remainder = slot.safeInsert(remainder);
				if (remainder.isEmpty()) return ItemStack.EMPTY;
			}
		}
		return remainder;
	}
}
