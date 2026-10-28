package me.ronaldi2001.modutils.nms;

import java.util.Arrays;
import net.minecraft.world.entity.vehicle.ContainerEntity;
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
 * The Minecraft calls that changed between versions, as they are in 1.20.5 and 1.20.6. Unlike the newest form (common), this is a
 * complete copy: the older versions do not stack on each other.
 */
@SuppressWarnings("unused") // the shared code that calls this is only compiled into the merged adapter
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
		return ingredient.isEmpty();
	}

	@SuppressWarnings("deprecation") // the holder of an item is only deprecated for plugins
	public static Stream<Holder<Item>> items(Ingredient ingredient) {
		return Arrays.stream(ingredient.getItems()).map(stack -> stack.getItem().builtInRegistryHolder());
	}

	/** The base ingredient of a smithing recipe, or null if it has none (it is optional before 1.21.5). */
	public static @Nullable Ingredient smithingBase(SmithingTransformRecipe recipe) {
		return null; // the ingredients of a smithing recipe are private before 1.21.2
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

	/** There is no item model component before 1.21.2. */
	public static @Nullable ResourceLocation itemModel(ItemStack stack) {
		return null;
	}

	/** Whether the entity container has a loot table set (it has not been opened yet). */
	public static boolean hasLootTable(ContainerEntity entity) {
		return entity.getLootTable() != null;
	}
}
