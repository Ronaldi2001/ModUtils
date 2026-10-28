package me.ronaldi2001.modutils.nms;

import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.nms.compat.jade.ItemWriter;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps each player's {@link ItemWriter}, so the ViaVersion mappings aren't built again for every request. A client's
 * version doesn't change while it is connected, so a writer only goes stale when the player changes their language.
 */
public final class ItemWriterCache {
	private record Entry(Locale locale, ItemWriter writer) {
	}

	private final ModUtilsPlugin plugin;
	private final Map<UUID, Entry> writers = new ConcurrentHashMap<>();

	public ItemWriterCache(ModUtilsPlugin plugin) {
		this.plugin = plugin;
	}

	public ItemWriter forPlayer(Player player) {
		Locale locale = ServerApi.locale(player);
		UUID id = player.getUniqueId();

		Entry entry = this.writers.get(id);
		if (entry != null && entry.locale().equals(locale)) return entry.writer();

		ItemTranslator translator = ItemTranslator.forPlayer(this.plugin, player);
		ItemWriter writer = translator == null ? ItemWriter.VANILLA : ItemWriter.translating(translator);
		this.writers.put(id, new Entry(locale, writer));
		return writer;
	}

	public void remove(UUID id) {
		this.writers.remove(id);
	}

	public void clear() {
		this.writers.clear();
	}
}
