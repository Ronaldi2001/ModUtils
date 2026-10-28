package me.ronaldi2001.modutils.nms.compat.jade.providers;

import net.minecraft.resources.Identifier;

final class JadeIds {
	static final Identifier FURNACE = mc("furnace");
	static final Identifier BREWING_STAND = mc("brewing_stand");
	static final Identifier BEEHIVE = mc("beehive");
	static final Identifier JUKEBOX = mc("jukebox");
	static final Identifier OBJECT_NAME = Identifier.fromNamespaceAndPath("jade", "object_name");
	static final Identifier ITEM_STORAGE = mc("item_storage");
	static final Identifier ITEM_STORAGE_DEFAULT = mc("item_storage.default");
	static final Identifier MOB_GROWTH = mc("mob_growth");
	static final Identifier MOB_BREEDING = mc("mob_breeding");
	static final Identifier ANIMAL_OWNER = mc("animal_owner");
	static final Identifier NEXT_ENTITY_DROP = mc("next_entity_drop");

	private JadeIds() {
	}

	private static Identifier mc(String path) {
		return Identifier.fromNamespaceAndPath("minecraft", path);
	}
}
