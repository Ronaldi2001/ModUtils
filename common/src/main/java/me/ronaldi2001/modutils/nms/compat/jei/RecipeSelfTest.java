package me.ronaldi2001.modutils.nms.compat.jei;

import io.netty.buffer.Unpooled;
import me.ronaldi2001.modutils.nms.Nms;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * A check of {@link RecipeTranscoder} that needs no client (run when the JEI debug option is on): every recipe of the
 * server is written in each of the other recipe layouts and then back in the server's own, which its own codec must
 * then read to the last byte. A layout that is read or written wrongly fails that, or throws.
 */
final class RecipeSelfTest {
	private static final int MAX_REPORTED = 12;

	private RecipeSelfTest() {
	}

	static void run(Logger logger, RecipeDisplayConfig display, int serverProtocol) {
		RecipeTranscoder.Layout source = RecipeTranscoder.Layout.forProtocol(serverProtocol);
		if (source == null) return;

		MinecraftServer server = MinecraftServer.getServer();
		RegistryAccess registries = server.registryAccess();
		List<String> failures = new ArrayList<>();
		int checked = 0;
		int unrepresentable = 0;

		for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
			Recipe<?> recipe = holder.value();
			if (recipe.isSpecial()) continue;

			RecipeSerializer<?> serializer = recipe.getSerializer();
			String serializerId = String.valueOf(BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer));
			byte[] original = encode(recipe, registries);
			if (original == null) continue;

			for (RecipeTranscoder.Layout other : RecipeTranscoder.Layout.values()) {
				if (other == source) continue;

				String key = Nms.recipeId(holder).toString();
				try {
					byte[] there = new RecipeTranscoder(source, other, null, Locale.ROOT, display, null, false)
							.transcode(serializerId, original, registries, false, recipe, key);
					if (there == null) {
						unrepresentable++;
						continue;
					}
					byte[] back = new RecipeTranscoder(other, source, null, Locale.ROOT, display, null, false)
							.transcode(serializerId, there, registries, false, recipe, key);
					if (back == null) {
						failures.add(key + " (" + serializerId + ") " + source + " > " + other + " > " + source + ": not readable back");
						continue;
					}
					String problem = decode(serializer, back, registries);
					if (problem != null) {
						failures.add(key + " (" + serializerId + ") " + source + " > " + other + " > " + source + ": " + problem);
					}
					checked++;
				} catch (Throwable e) {
					failures.add(key + " (" + serializerId + ") " + source + " > " + other + ": " + e);
				}
			}
		}

		logger.info("[JEI] Recipe self-test on layout " + source + ": " + checked + " conversions checked, " + unrepresentable
				+ " not representable in the other layout, " + failures.size() + " failed");
		failures.stream().limit(MAX_REPORTED).forEach(line -> logger.warning("[JEI]   " + line));
	}

	/** The recipe as the server's own codec writes it, without the serializer id in front. */
	private static byte[] encode(Recipe<?> recipe, RegistryAccess registries) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		try {
			Recipe.STREAM_CODEC.encode(buf, recipe);
			buf.readVarInt();
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			return bytes;
		} catch (Throwable e) {
			return null;
		} finally {
			buf.release();
		}
	}

	/** @return null if the server's codec reads the body completely, otherwise what went wrong */
	private static String decode(RecipeSerializer<?> serializer, byte[] body, RegistryAccess registries) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		try {
			buf.writeVarInt(BuiltInRegistries.RECIPE_SERIALIZER.getId(serializer));
			buf.writeBytes(body);
			Recipe.STREAM_CODEC.decode(buf);
			return buf.readableBytes() == 0 ? null : buf.readableBytes() + " bytes left over";
		} catch (Throwable e) {
			return e.toString();
		} finally {
			buf.release();
		}
	}
}
