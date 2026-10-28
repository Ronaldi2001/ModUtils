package me.ronaldi2001.modutils.pluginHooks.via;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaAPI;
import com.viaversion.viaversion.api.data.FullMappings;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.logging.Level;

public class ViaVersionHook {

	private final ViaAPI<Player> api;
	private final ModUtilsPlugin plugin;

	private ViaVersionHook(ModUtilsPlugin plugin, ViaAPI<Player> api) {
		this.plugin = plugin;
		this.api = api;
	}

	@Nullable
	@SuppressWarnings("unchecked")
	public static ViaVersionHook load(ModUtilsPlugin plugin) {
		if (!Bukkit.getPluginManager().isPluginEnabled("ViaVersion")) {
			return null;
		}

		try {
			ViaAPI<Player> api = (ViaAPI<Player>) Via.getAPI();
			plugin.getLogger().info("ViaVersionHook hooked into ViaVersion.");
			return new ViaVersionHook(plugin, api);
		} catch (Exception e) {
			return null;
		}
	}

	public ViaAPI<Player> getApi() {
		return this.api;
	}

	public int getPlayerVersion(Player player) {
		return this.api.getPlayerVersion(player);
	}

	/**
	 * The oldest and newest Minecraft versions of the clients ViaVersion lets in, kept within what this plugin
	 * supports: no older than {@code oldest} and no newer than {@code newest}.
	 *
	 * @return {oldest, newest}
	 */
	public String[] getSupportedRange(String oldest, String newest) {
		SortedSet<ProtocolVersion> versions = this.api.getSupportedProtocolVersions();
		ProtocolVersion min = versions.first();
		ProtocolVersion max = versions.last();
		ProtocolVersion ourOldest = ProtocolVersion.getClosest(oldest);
		ProtocolVersion ourNewest = ProtocolVersion.getClosest(newest);
		if (ourOldest != null && min.olderThan(ourOldest)) min = ourOldest;
		if (ourNewest != null && max.newerThan(ourNewest)) max = ourNewest;

		// A protocol that covers several releases is named like "1.20.5-1.20.6"
		String minName = min.getName();
		String maxName = max.getName();
		return new String[]{minName.substring(0, minName.indexOf('-') < 0 ? minName.length() : minName.indexOf('-')),
				maxName.substring(maxName.lastIndexOf('-') + 1)};
	}

	/** The Minecraft version the player's client is on, e.g. {@code 1.21.8}. */
	public String getPlayerVersionName(Player player) {
		return ProtocolVersion.getProtocol(this.api.getPlayerVersion(player)).getName();
	}

	/**
	 * @return the mappings for rewriting items for this player's client version, or null if the client is on the
	 * server's version (the vanilla codecs can be used as they are) or no conversion path exists.
	 */
	@Nullable
	public ViaMappings createMappings(Player player) {
		try {
			ProtocolVersion clientVersion = this.api.getPlayerProtocolVersion(player);
			ProtocolVersion serverVersion = this.api.getServerVersion().lowestSupportedProtocolVersion();
			if (clientVersion.equals(serverVersion)) return null;

			List<ProtocolPathEntry> path = Via.getManager().getProtocolManager().getProtocolPath(clientVersion, serverVersion);
			if (path == null) return null;

			// The path runs client -> server, but ids need translating server -> client
			List<MappingData> chain = new ArrayList<>();
			for (ProtocolPathEntry entry : path.reversed()) {
				MappingData mappings = entry.protocol().getMappingData();
				if (mappings != null) chain.add(mappings);
			}

			return new ViaMappings(id -> {
				for (MappingData mappings : chain) {
					id = mappings.getNewItemId(id);
					if (id < 0) return -1;
				}
				return id;
			}, componentId(chain, "minecraft:item_model"), componentId(chain, "minecraft:custom_model_data"),
					clientVersion.olderThan(ProtocolVersion.v1_21_4),
					componentId(chain, "minecraft:item_name"), componentId(chain, "minecraft:custom_name"),
					componentId(chain, "minecraft:lore"), componentId(chain, "minecraft:custom_data"));
		} catch (Exception e) {
			this.plugin.getLogger().log(Level.WARNING, "Could not build item translation for " + player.getName(), e);
			return null;
		}
	}

	/**
	 * The component's id on the client's version, or -1 if ViaVersion has no mapping for it.
	 */
	private static int componentId(List<MappingData> chain, String key) {
		// Later steps that don't remap components leave ids unchanged, so the last step that does is the client's
		for (MappingData mappings : chain.reversed()) {
			FullMappings components = mappings.getDataComponentSerializerMappings();
			if (components != null) return components.mappedId(key);
		}
		return -1;
	}
}
