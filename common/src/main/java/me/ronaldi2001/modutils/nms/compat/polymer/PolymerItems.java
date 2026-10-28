package me.ronaldi2001.modutils.nms.compat.polymer;

import me.ronaldi2001.modutils.nms.ServerApi;
import me.ronaldi2001.modutils.nms.Nms;
import me.ronaldi2001.modutils.nms.AdventureBridge;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The custom items Polymer clients are told about. This server has no registered custom items: an item counts as
 * custom when it looks different from the plain item it is made of (a custom {@code item_model} or
 * {@code custom_model_data}), and two stacks that look the same are the same custom item.
 */
final class PolymerItems {
	/** The custom data key Polymer reads an item's identity from. */
	static final String STACK_KEY = "$polymer:stack";

	/**
	 * Polymer's own numbering for these items, separate from the game's item ids. Starts well above the vanilla ids.
	 */
	private static final int FIRST_ID = 5000;

	/**
	 * @param numId  the number the client knows the item by (and the recipes refer to)
	 * @param id     the item's name
	 * @param visual how the item looks, already carrying its marker
	 */
	record Entry(int numId, Identifier id, ItemStack visual) {
	}

	/**
	 * What makes an item count as a custom item (see the polymer section of the config).
	 *
	 * @param itemModel       has an {@code item_model}
	 * @param customModelData has {@code custom_model_data}
	 * @param customData      has custom data, which is where plugin data (PDC) is stored
	 * @param valueKeys       custom data keys (or namespaces) whose value also tells items apart; for the others only the key counts
	 * @param ignoreKeys      custom data keys (or namespaces) that are left out completely
	 */
	record Detection(boolean itemModel, boolean customModelData, boolean customData, Set<String> valueKeys,
					 Set<String> ignoreKeys) {
		static final Detection DEFAULT = new Detection(true, true, false, Set.of(), Set.of());

		/** Whether a key matches one of the patterns: a full key, a namespace ({@code rawhub}) or a pattern with *. */
		static boolean matches(Set<String> patterns, String key) {
			for (String pattern : patterns) {
				if (pattern.equals(key)) return true;
				if (!pattern.contains(":") && !pattern.contains("*") && key.startsWith(pattern + ":")) return true;
				if (pattern.contains("*") && key.matches(java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q"))) return true;
			}
			return false;
		}
	}

	private volatile Detection detection = Detection.DEFAULT;
	private volatile java.util.function.@Nullable Consumer<String> onNew;

	/** Called with a description of every custom item that is registered (for the debug log). */
	void onNew(java.util.function.Consumer<String> onNew) {
		this.onNew = onNew;
	}

	void detection(Detection detection) {
		this.detection = detection;
	}

	private final Map<String, Entry> byKey = new ConcurrentHashMap<>();
	private final AtomicInteger nextId = new AtomicInteger(FIRST_ID);

	List<Entry> all() {
		return new ArrayList<>(this.byKey.values());
	}

	/**
	 * @return the custom item this stack is, registering it if it is new, or null for a plain stack
	 */
	@Nullable Entry identify(ItemStack stack) {
		if (stack.isEmpty() || !this.looksCustom(stack)) return null;

		String key = this.keyOf(stack);
		Entry known = this.byKey.get(key);
		if (known != null) return known;

		return this.byKey.computeIfAbsent(key, k -> {
			Identifier base = BuiltInRegistries.ITEM.getKey(stack.getItem());
			Identifier id = Identifier.fromNamespaceAndPath("modcompat", base.getPath() + "/" + hash(k));
			java.util.function.Consumer<String> log = this.onNew;
			if (log != null) log.accept(id + " <- " + k);
			Entry entry = new Entry(this.nextId.getAndIncrement(), id, stack.copyWithCount(1));
			return new Entry(entry.numId(), id, tag(entry.visual(), entry));
		});
	}

	private boolean looksCustom(ItemStack stack) {
		Detection detection = this.detection;
		return detection.itemModel() && Nms.itemModel(stack) != null
				|| detection.customModelData() && stack.get(DataComponents.CUSTOM_MODEL_DATA) != null
				|| detection.customData() && !customDataKeys(stack, Detection.DEFAULT).isEmpty();
	}

	/** How a stack looks: its key without the custom data. */
	String lookKey(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString() + '|' + Objects.toString(Nms.itemModel(stack), "") +
				'|' + stack.get(DataComponents.CUSTOM_MODEL_DATA) +
				'|' + (Nms.hasNonDefault(stack, DataComponents.ITEM_NAME) ? textOf(stack.get(DataComponents.ITEM_NAME)) : "") +
				'|' + textOf(stack.get(DataComponents.CUSTOM_NAME));
	}

	/** What makes two custom stacks the same item: the base item and everything that picks how it looks. */
	private String keyOf(ItemStack stack) {
		Detection detection = this.detection;
		StringBuilder key = new StringBuilder(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		key.append('|').append(Objects.toString(Nms.itemModel(stack), ""));
		key.append('|').append(stack.get(DataComponents.CUSTOM_MODEL_DATA));
		key.append('|').append(Nms.hasNonDefault(stack, DataComponents.ITEM_NAME) ? textOf(stack.get(DataComponents.ITEM_NAME)) : "");
		key.append('|').append(textOf(stack.get(DataComponents.CUSTOM_NAME)));
		if (detection.customData()) key.append('|').append(customDataKeys(stack, detection));
		return key.toString();
	}

	/**
	 * The custom data keys of a stack as sorted text, with the value for the keys that are listed. Plugin data (PDC)
	 * lives one level down, in {@code PublicBukkitValues}, so its keys are looked at too.
	 */
	private static String customDataKeys(ItemStack stack, Detection detection) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return "";

		CompoundTag tag = data.copyTag();
		tag.remove(STACK_KEY);
		TreeSet<String> keys = new TreeSet<>();
		for (String name : Nms.keys(tag)) {
			if (name.equals("PublicBukkitValues")) {
				CompoundTag pdc = Nms.getCompound(tag, name);
				for (String pdcKey : Nms.keys(pdc)) addKey(keys, pdcKey, pdc.get(pdcKey), detection);
			} else {
				addKey(keys, name, tag.get(name), detection);
			}
		}
		return String.join(",", keys);
	}

	private static void addKey(Set<String> keys, String name, @Nullable Tag value, Detection detection) {
		if (Detection.matches(detection.ignoreKeys(), name)) return;
		keys.add(value != null && Detection.matches(detection.valueKeys(), name) ? name + "=" + value : name);
	}

	/**
	 * A name as plain text. Not the component's own toString: the same name can be a vanilla component or one wrapped by
	 * Paper, and the wrapper's toString is an object address that differs every time.
	 */
	private static String textOf(@Nullable Component name) {
		if (name == null) return "";
		try {
			// Through Paper's own conversion, which works for its wrapped components as well
			return PlainTextComponentSerializer.plainText().serialize(AdventureBridge.asAdventure(name));
		} catch (Throwable e) {
			return String.valueOf(name.getContents());
		}
	}

	private static String hash(String key) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 5);
		} catch (Exception e) {
			return Integer.toHexString(key.hashCode());
		}
	}

	/**
	 * The stack with Polymer's marker added to its custom data, which is what the client reads the item from.
	 */
	static ItemStack tag(ItemStack stack, Entry entry) {
		ItemStack out = stack.copy();
		CustomData data = out.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
		CompoundTag marker = new CompoundTag();
		marker.putString("id", entry.id().toString());
		tag.put(STACK_KEY, marker);
		out.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return out;
	}

	/**
	 * The stack without the marker.
	 */
	static ItemStack untag(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null || !ServerApi.hasKey(data, STACK_KEY)) return stack;

		ItemStack out = stack.copy();
		CompoundTag tag = data.copyTag();
		tag.remove(STACK_KEY);
		if (tag.isEmpty()) {
			out.remove(DataComponents.CUSTOM_DATA);
		} else {
			out.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		}
		return out;
	}
}
