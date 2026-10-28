package me.ronaldi2001.modutils.nms;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;

/**
 * A Bukkit server has no PaperAdventure, so a component goes through its JSON form, which both
 * sides read and write.
 */
@SuppressWarnings("unused") // the shared code that calls this is only compiled into the merged adapter
public final class AdventureBridge {
	private AdventureBridge() {
	}

	private static RegistryOps<JsonElement> ops() {
		return ((CraftServer) Bukkit.getServer()).getServer().registryAccess().createSerializationContext(JsonOps.INSTANCE);
	}

	public static Component asVanilla(net.kyori.adventure.text.Component component) {
		JsonElement json = GsonComponentSerializer.gson().serializeToTree(component);
		return ComponentSerialization.CODEC.parse(ops(), json).result().orElseGet(Component::empty);
	}

	public static net.kyori.adventure.text.Component asAdventure(Component component) {
		JsonElement json = ComponentSerialization.CODEC.encodeStart(ops(), component).result().orElse(null);
		return json == null ? net.kyori.adventure.text.Component.empty() : GsonComponentSerializer.gson().deserializeFromTree(json);
	}
}
