package me.ronaldi2001.modutils.pluginHooks.via;

import java.util.function.IntUnaryOperator;

/**
 * What ViaVersion knows about translating items for one client, without any Minecraft types so it can live in the
 * version-independent core.
 *
 * @param itemIds                    maps a server item id to the client's id, or to a negative number if it has none
 * @param itemModelComponentId       id of {@code minecraft:item_model} on the client, or -1
 * @param customModelDataComponentId id of {@code minecraft:custom_model_data} on the client, or -1
 * @param customModelDataAsInt       true for clients before 1.21.4
 * @param customDataComponentId      id of {@code minecraft:custom_data} on the client, or -1
 */
public record ViaMappings(IntUnaryOperator itemIds, int itemModelComponentId, int customModelDataComponentId,
						  boolean customModelDataAsInt, int itemNameComponentId, int customNameComponentId,
						  int loreComponentId, int customDataComponentId) {
}
