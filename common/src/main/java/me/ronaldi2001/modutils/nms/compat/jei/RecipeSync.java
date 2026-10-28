package me.ronaldi2001.modutils.nms.compat.jei;

import io.netty.buffer.Unpooled;
import me.ronaldi2001.modutils.nms.Nms;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.nms.ItemTranslator;
import me.ronaldi2001.modutils.nms.compat.polymer.PolymerHook;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Sends the server's recipes to JEI, which is how it learns about server and datapack recipes. Without it JEI only
 * knows the vanilla recipes bundled in the client. Based on the recipe sync of the standalone JEI-Plugin.
 * <p>
 * Each loader has its own payload for this, with the same recipes in a different wrapping (see {@link Platform}):
 * Fabric API's {@code fabric:recipe_sync}, and NeoForge's {@code neoforge:recipe_content}, which NeoForge itself only
 * sends to NeoForge servers.
 * <p>
 * Every client gets the recipes re-encoded by {@link RecipeTranscoder} into the layout of its own version, since
 * the recipe layout differs between versions and custom results are replaced by barriers. Payloads are cached per
 * loader, client version and language, because they are identical for every player on it.
 */
final class RecipeSync {
	/**
	 * The channel a Fabric client registers once it can receive the sync.
	 */
	static final String CHANNEL = "fabric:recipe_sync";

	/**
	 * Which loader's payload the recipes are wrapped in.
	 */
	enum Platform {
		FABRIC,
		/**
		 * NeoForge posts an empty recipe event for every recipe update it gets from a non-NeoForge server, which JEI
		 * reads as "no server recipes" and drops what it had. The recipes therefore have to be sent <b>after</b> the
		 * recipe update, not before it like on Fabric.
		 */
		NEOFORGE
	}

	/**
	 * @return whether the client's mod can take this loader's recipe sync at all. Fabric API only has the sync from
	 * 1.21.10 (protocol 773) and NeoForge from 1.21.4 (769); older clients get nothing.
	 */
	static boolean notSupported(Platform platform, int clientProtocol) {
		return platform == Platform.FABRIC ? clientProtocol < 773 : clientProtocol < 769;
	}

	/**
	 * The recipe types JEI asks a NeoForge server for. Other recipes are left out of the payload.
	 */
	private static final List<RecipeType<?>> NEOFORGE_TYPES = List.of(
			RecipeType.CRAFTING, RecipeType.STONECUTTING, RecipeType.SMELTING, RecipeType.SMOKING,
			RecipeType.BLASTING, RecipeType.CAMPFIRE_COOKING, RecipeType.SMITHING
	);

	/**
	 * The registry ids NeoForge uses for the recipe serializers and types on 26.1, 26.2 and 26.3. NeoForge writes both
	 * as registry ids, so they are the client's own numbering and not the server's, and have to be spelled out.
	 * <p>
	 * Read from the registration order in the client jars. These are the same on all three versions: 26.3's added
	 * brewing serializer and type come after the ones listed here. Entries a mod adds to the registries come after the
	 * vanilla ones, so they don't move these.
	 */
	private static final Map<String, Integer> SERIALIZER_IDS_26 = Map.of(
			"minecraft:crafting_shaped", 0,
			"minecraft:crafting_shapeless", 1,
			"minecraft:crafting_transmute", 4,
			"minecraft:smelting", 14,
			"minecraft:blasting", 15,
			"minecraft:smoking", 16,
			"minecraft:campfire_cooking", 17,
			"minecraft:stonecutting", 18,
			"minecraft:smithing_transform", 19
	);
	/**
	 * The same for 1.21.3 to 1.21.11, which number the serializers differently (read from their registry reports,
	 * which are the same for all of them). The recipe types are numbered the same in all versions.
	 */
	private static final Map<String, Integer> SERIALIZER_IDS_1_21 = Map.of(
			"minecraft:crafting_shaped", 0,
			"minecraft:crafting_shapeless", 1,
			"minecraft:crafting_transmute", 12,
			"minecraft:smelting", 14,
			"minecraft:blasting", 15,
			"minecraft:smoking", 16,
			"minecraft:campfire_cooking", 17,
			"minecraft:stonecutting", 18,
			"minecraft:smithing_transform", 19
	);
	private static final Map<String, Integer> TYPE_IDS_26 = Map.of(
			"minecraft:crafting", 0,
			"minecraft:smelting", 1,
			"minecraft:blasting", 2,
			"minecraft:smoking", 3,
			"minecraft:campfire_cooking", 4,
			"minecraft:stonecutting", 5,
			"minecraft:smithing", 6
	);

	/**
	 * Recipes in these namespaces are vanilla; everything else is treated as a custom recipe.
	 */
	private static final String VANILLA_NAMESPACE = "minecraft";

	/**
	 * A packet's length prefix is 3 bytes, so nothing larger than this can be sent.
	 */
	private static final int MAX_PAYLOAD_BYTES = 2_000_000;

	private final ModUtilsPlugin plugin;
	private final boolean debug;
	private final RecipeDisplayConfig display;
	private final PolymerHook polymer;

	private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
	private volatile int cachedRecipeCount = -1;

	RecipeSync(ModUtilsPlugin plugin, boolean debug, RecipeDisplayConfig display, PolymerHook polymer) {
		this.plugin = plugin;
		this.polymer = polymer;
		this.debug = debug;
		this.display = display;
	}

	void invalidate() {
		this.cache.clear();
		this.cachedRecipeCount = -1;
	}

	/**
	 * Builds the payloads for a client on the server's own version, so that the first player to join does not wait for
	 * them. Building them the first time takes seconds, during which that player's client has no recipe update, so
	 * JEI has not started, and starts without the server's recipes if anything opens a screen in the meantime. Meant to
	 * be run off the main thread, once the recipes are loaded.
	 *
	 * @param locale the language the custom items' names are written in
	 */
	void warmUp(Locale locale) {
		int protocol = SharedConstants.getProtocolVersion();
		RecipeTranscoder.Layout layout = RecipeTranscoder.Layout.forProtocol(protocol);
		if (layout == null) return;

		MinecraftServer server = MinecraftServer.getServer();
		long start = System.nanoTime();
		int built = 0;
		for (Platform platform : Platform.values()) {
			if (notSupported(platform, protocol)) continue;
			// The same key as in send(), for a client on the server's version without Polymer
			if (this.payload(platform, server.getRecipeManager(), server.registryAccess(), layout, null, locale,
					platform + ":" + protocol + ":" + locale, false, false) != null) built++;
		}
		this.debug("Built " + built + " recipe payloads ahead of time in " + (System.nanoTime() - start) / 1_000_000L + " ms");
	}

	/**
	 * Sends the recipes. For Fabric the vanilla recipe update follows them: JEI (re)starts when that packet arrives, so
	 * the recipes have to be there before it. On NeoForge it is the other way around and the caller sends the update
	 * first (see {@link Platform#NEOFORGE}).
	 *
	 * @param platform   which loader's payload to send
	 * @param layout     the recipe layout of the client's version
	 * @param translator null for a client on the server's version
	 * @param cacheKey   the client's protocol version and language
	 */
	void send(Platform platform, Player bukkitPlayer, ServerPlayer player, RecipeTranscoder.Layout layout,
			  @Nullable ItemTranslator translator, Locale locale, String cacheKey) {
		// Not player.level(): ServerLevel is closeable, and this isn't ours to close
		RecipeManager recipeManager = MinecraftServer.getServer().getRecipeManager();

		boolean known = this.polymer.knowsCustomItems(bukkitPlayer.getUniqueId());
		boolean extended = known && this.polymer.extendedIngredients(bukkitPlayer.getUniqueId());
		byte[] payload = this.payload(platform, recipeManager, player.registryAccess(), layout, translator, locale,
				platform + ":" + cacheKey + (known ? (extended ? ":polymer+" : ":polymer") : ""), known, extended);
		if (payload == null) return;
		// The results and ingredients refer to custom items, which the client has to know first
		if (known) this.polymer.ensureSynced(bukkitPlayer);

		Identifier channel = platform == Platform.NEOFORGE
				? Identifier.fromNamespaceAndPath("neoforge", "recipe_content")
				: Identifier.fromNamespaceAndPath("fabric", "recipe_sync");
		player.connection.send(Nms.customPayload(channel, payload));
		if (platform == Platform.FABRIC) this.sendUpdateOnly(player);
		this.debug("Sent " + payload.length + " bytes of " + platform + " recipes to " + bukkitPlayer.getName());
	}

	/**
	 * The vanilla recipe update: what Paper sends when a player joins, and what JEI starts on.
	 */
	void sendUpdateOnly(ServerPlayer player) {
		RecipeManager recipeManager = MinecraftServer.getServer().getRecipeManager();
		player.connection.send(Nms.recipeUpdate(recipeManager));
	}

	private byte @Nullable [] payload(Platform platform, RecipeManager recipeManager, RegistryAccess registryAccess,
									  RecipeTranscoder.Layout layout, @Nullable ItemTranslator translator, Locale locale,
									  String cacheKey, boolean known, boolean extended) {
		Collection<RecipeHolder<?>> all = recipeManager.getRecipes();
		if (this.cachedRecipeCount != all.size()) {
			this.cache.clear();
			this.cachedRecipeCount = all.size();
		}

		byte[] cached = this.cache.get(cacheKey);
		if (cached != null) return cached;

		try {
			RecipeTranscoder transcoder = new RecipeTranscoder(
					RecipeTranscoder.Layout.forProtocol(SharedConstants.getProtocolVersion()), layout, translator, locale,
					this.display, known ? this.polymer : null, extended
			);
			byte[] built = platform == Platform.NEOFORGE
					? this.buildNeoForge(all, registryAccess, transcoder, layout)
					: this.buildFabric(all, registryAccess, transcoder);
			if (built.length > MAX_PAYLOAD_BYTES) {
				this.plugin.getLogger().warning("JEI recipe sync is " + built.length + " bytes, too large to send; skipping");
				return null;
			}
			this.cache.put(cacheKey, built);
			return built;
		} catch (Exception e) {
			this.plugin.getLogger().log(Level.WARNING, "Failed to build the JEI recipe sync", e);
			return null;
		}
	}

	/**
	 * One serializer's already-encoded recipes.
	 */
	private record Entry(String serializerId, List<byte[]> recipes) {
	}

	/**
	 * Fabric API's recipe sync: recipes grouped by serializer, each as its id followed by its body.
	 */
	private byte[] buildFabric(Collection<RecipeHolder<?>> all, RegistryAccess registryAccess, RecipeTranscoder transcoder) {
		// Group by serializer in a stable order, skipping recipes the client can't take (see isSyncable)
		Map<RecipeSerializer<?>, List<RecipeHolder<?>>> grouped = new LinkedHashMap<>();
		all.stream()
				.filter(this::isSyncable)
				.sorted(Comparator.comparing(holder -> Nms.recipeId(holder).toString()))
				.forEach(holder -> grouped.computeIfAbsent(holder.value().getSerializer(), k -> new ArrayList<>()).add(holder));

		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<RecipeSerializer<?>, List<RecipeHolder<?>>> group : grouped.entrySet()) {
			String serializerId = this.serializerId(group.getKey());
			if (serializerId == null) continue;

			List<byte[]> encoded = this.encodeRecipes(serializerId, group.getValue(), registryAccess, transcoder);
			if (!encoded.isEmpty()) entries.add(new Entry(serializerId, encoded));
		}
		entries.sort(Comparator.comparing(Entry::serializerId));

		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
		try {
			out.writeVarInt(entries.size());
			for (Entry entry : entries) {
				out.writeUtf(entry.serializerId());
				out.writeVarInt(entry.recipes().size());
				entry.recipes().forEach(out::writeBytes);
			}
			byte[] bytes = new byte[out.readableBytes()];
			out.readBytes(bytes);
			return bytes;
		} finally {
			out.release();
		}
	}

	/**
	 * NeoForge's {@code RecipeContentPayload}: the recipe types, then each recipe as its id, the serializer's registry
	 * id and its body. Only recipes of the types JEI asks for are included.
	 * <p>
	 * The serializer and recipe types are registry ids. For a client on the server's version these are the server's
	 * own; for newer clients they come from {@link #SERIALIZER_IDS_26} and {@link #TYPE_IDS_26}.
	 */
	private byte[] buildNeoForge(Collection<RecipeHolder<?>> all, RegistryAccess registryAccess, RecipeTranscoder transcoder,
								 RecipeTranscoder.Layout layout) {
		// A client on the server's own version uses the server's registry ids; any other has its own numbering
		boolean serverIds = transcoder.sameVersion();
		Map<String, Integer> clientSerializerIds = layout.usesTemplates() ? SERIALIZER_IDS_26 : SERIALIZER_IDS_1_21;
		List<byte[]> encoded = new ArrayList<>();
		all.stream()
				.filter(this::isSyncable)
				.filter(holder -> NEOFORGE_TYPES.contains(holder.value().getType()))
				.sorted(Comparator.comparing(holder -> Nms.recipeId(holder).toString()))
				.forEach(holder -> {
					RecipeSerializer<?> serializer = holder.value().getSerializer();
					String serializerId = this.serializerId(serializer);
					if (serializerId == null) return;

					int clientSerializerId;
					if (serverIds) {
						clientSerializerId = BuiltInRegistries.RECIPE_SERIALIZER.getId(serializer);
					} else {
						Integer mapped = clientSerializerIds.get(serializerId);
						if (mapped == null) return;
						clientSerializerId = mapped;
					}

					byte[] body = this.encodeBody(holder, serializerId, registryAccess, transcoder);
					if (body == null) return;

					RegistryFriendlyByteBuf entry = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
					try {
						Nms.writeRecipeId(entry, holder);
						entry.writeVarInt(clientSerializerId);
						entry.writeBytes(body);
						byte[] bytes = new byte[entry.readableBytes()];
						entry.readBytes(bytes);
						encoded.add(bytes);
					} finally {
						entry.release();
					}
				});

		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
		try {
			out.writeVarInt(NEOFORGE_TYPES.size());
			for (RecipeType<?> type : NEOFORGE_TYPES) out.writeVarInt(this.typeId(type, serverIds));
			out.writeVarInt(encoded.size());
			encoded.forEach(out::writeBytes);
			byte[] bytes = new byte[out.readableBytes()];
			out.readBytes(bytes);
			return bytes;
		} finally {
			out.release();
		}
	}

	/**
	 * @return the serializer's registry name, or null for one that isn't registered
	 */
	private @Nullable String serializerId(RecipeSerializer<?> serializer) {
		Identifier key = BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer);
		return key == null ? null : key.toString();
	}

	private List<byte[]> encodeRecipes(
			String serializerId,
			List<RecipeHolder<?>> recipes,
			RegistryAccess registryAccess,
			RecipeTranscoder transcoder
	) {
		// Each recipe is encoded on its own so one that fails is dropped instead of breaking the whole packet
		List<byte[]> encoded = new ArrayList<>(recipes.size());
		for (RecipeHolder<?> holder : recipes) {
			byte[] recipeBytes = this.encodeBody(holder, serializerId, registryAccess, transcoder);
			if (recipeBytes == null) continue;

			RegistryFriendlyByteBuf key = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
			try {
				Nms.writeRecipeId(key, holder);
				byte[] keyBytes = new byte[key.readableBytes()];
				key.readBytes(keyBytes);

				byte[] full = new byte[keyBytes.length + recipeBytes.length];
				System.arraycopy(keyBytes, 0, full, 0, keyBytes.length);
				System.arraycopy(recipeBytes, 0, full, keyBytes.length, recipeBytes.length);
				encoded.add(full);
			} finally {
				key.release();
			}
		}
		return encoded;
	}

	/**
	 * The registry id the client knows a recipe type by.
	 */
	private int typeId(RecipeType<?> type, boolean serverIds) {
		if (serverIds) return BuiltInRegistries.RECIPE_TYPE.getId(type);

		String name = String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(type));
		Integer id = TYPE_IDS_26.get(name);
		if (id == null) throw new IllegalStateException("No client id for recipe type " + name);
		return id;
	}

	/**
	 * One recipe's body (without its id or serializer) in the client's layout.
	 *
	 * @return null if it can't be represented on the client, or failed to encode
	 */
	private byte @Nullable [] encodeBody(
			RecipeHolder<?> holder,
			String serializerId,
			RegistryAccess registryAccess,
			RecipeTranscoder transcoder
	) {
		RegistryFriendlyByteBuf temp = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
		try {
			// The recipe codec writes the serializer's registry id and then the body. Only the body is wanted here, as
			// both loaders write the id themselves.
			Recipe.STREAM_CODEC.encode(temp, holder.value());
			temp.readVarInt();
			byte[] recipeBytes = new byte[temp.readableBytes()];
			temp.readBytes(recipeBytes);

			boolean custom = !VANILLA_NAMESPACE.equals(Nms.recipeId(holder).getNamespace());
			byte[] transcoded = transcoder.transcode(serializerId, recipeBytes, registryAccess, custom, holder.value(),
					Nms.recipeId(holder).toString());
			if (transcoded == null) {
				this.debug("Skipping recipe " + Nms.recipeId(holder) + ": not representable on the client");
			}
			return transcoded;
		} catch (Exception e) {
			this.debug("Skipping recipe " + Nms.recipeId(holder) + ": " + e);
			return null;
		} finally {
			temp.release();
		}
	}

	/**
	 * Special recipes (armor dyeing, map cloning, ...) and recipes with an empty ingredient crash the client.
	 */
	private boolean isSyncable(RecipeHolder<?> holder) {
		Recipe<?> recipe = holder.value();
		return !recipe.isSpecial() && !Nms.hasEmptyIngredient(recipe);
	}

	private void debug(String message) {
		if (this.debug) this.plugin.getLogger().info("[JEI] " + message);
	}
}
