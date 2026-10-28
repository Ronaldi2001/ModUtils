package me.ronaldi2001.modutils.pluginHooks.bedrock;

import me.ronaldi2001.modutils.ModUtilsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Tells Bedrock players (joined through Geyser and/or Floodgate) apart from Java ones. They can't run any of the
 * client mods this plugin serves, so every compat skips them as early as it can.
 * <p>
 * Both APIs are reached through reflection, using the other plugin's own class loader, so there is nothing to compile
 * against and nothing breaks when either plugin is missing. Floodgate's UUIDs also always have their most significant
 * bits at zero, which is checked as well, so a Floodgate player is still caught if the API can't be reached.
 */
public final class BedrockHook {
	private final @Nullable Api floodgate;
	private final @Nullable Api geyser;

	private BedrockHook(@Nullable Api floodgate, @Nullable Api geyser) {
		this.floodgate = floodgate;
		this.geyser = geyser;
	}

	/**
	 * @return a hook that works whether Geyser or Floodgate is installed; it never returns null
	 */
	public static BedrockHook load(ModUtilsPlugin plugin) {
		Api floodgate = Api.find(plugin, "floodgate", "org.geysermc.floodgate.api.FloodgateApi", "getInstance", "isFloodgatePlayer");
		Api geyser = Api.find(plugin, "Geyser-Spigot", "org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer");
		if (floodgate != null) plugin.getLogger().info("BedrockHook hooked into Floodgate.");
		if (geyser != null) plugin.getLogger().info("BedrockHook hooked into Geyser.");
		return new BedrockHook(floodgate, geyser);
	}

	public boolean isFloodgateHooked() {
		return this.floodgate != null;
	}

	public boolean isGeyserHooked() {
		return this.geyser != null;
	}

	public boolean isBedrock(Player player) {
		return this.isBedrock(player.getUniqueId());
	}

	/**
	 * Works before the player object exists (the configuration phase), since only the UUID is needed.
	 */
	public boolean isBedrock(UUID id) {
		if (id.getMostSignificantBits() == 0L) return true;
		return this.floodgate != null && this.floodgate.test(id) || this.geyser != null && this.geyser.test(id);
	}

	/**
	 * One API: a static method that gives the instance, and an instance method that takes a UUID and returns a boolean.
	 */
	private record Api(Method instance, Method check) {
		static @Nullable Api find(ModUtilsPlugin plugin, String pluginName, String className, String instanceMethod, String checkMethod) {
			Plugin other = Bukkit.getPluginManager().getPlugin(pluginName);
			if (other == null || !other.isEnabled()) return null;

			try {
				Class<?> type = Class.forName(className, true, other.getClass().getClassLoader());
				return new Api(type.getMethod(instanceMethod), type.getMethod(checkMethod, UUID.class));
			} catch (Throwable e) {
				plugin.getLogger().warning("Found " + pluginName + " but could not use its API (" + e + "); Bedrock players "
						+ "are still recognised by their UUID if they come through Floodgate");
				return null;
			}
		}

		boolean test(UUID id) {
			try {
				return (Boolean) this.check.invoke(this.instance.invoke(null), id);
			} catch (Throwable e) {
				return false;
			}
		}
	}
}
