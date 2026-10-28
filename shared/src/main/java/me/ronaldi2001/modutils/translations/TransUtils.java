package me.ronaldi2001.modutils.translations;

import me.ronaldi2001.modutils.adapter.AdapterLoader;
import org.bukkit.plugin.PluginDescriptionFile;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.platform.Platform;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.Translator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.text.MessageFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;

public final class TransUtils {
	private static final Key TRANSLATOR_KEY = ModUtilsPlugin.getKey("translations");

	private static final String TRANSLATIONS_FOLDER = "translations";
	private static final String FILE_EXTENSION = ".yml";
	private static final String DEFAULT_FILE_NAME = "en_US" + FILE_EXTENSION;
	private static final String KEY_PREFIX = ModUtilsPlugin.PREFIX + ".";

	private static final Locale DEFAULT_LOCALE = Locale.US;

	private static Store currentStore;
	private static Platform platform;

	private TransUtils() {
	}

	/** Whether a translation key (without the plugin prefix) is registered. */
	public static boolean hasKey(@NotNull String key) {
		return currentStore != null && currentStore.contains(KEY_PREFIX + key);
	}

	/** Removes the translations from the {@link GlobalTranslator}. */
	public static void unload() {
		if (currentStore != null) {
			GlobalTranslator.translator().removeSource(currentStore);
			currentStore = null;
		}
		platform = null;
	}

	public static void load(ModUtilsPlugin plugin) {
		unload();
		platform = plugin.getPlatform();

		MiniMessage miniMessage = MiniMessage.builder()
				.tags(createGlobalTagResolver(plugin))
				.build();
		currentStore = new Store(miniMessage);

		File translationsFolder = new File(plugin.getDataFolder(), TRANSLATIONS_FOLDER);
		if (!translationsFolder.exists() && !translationsFolder.mkdirs()) {
			plugin.getLogger().severe("Could not create translations folder!");
			return;
		}

		syncDefaultTranslationFile(plugin, new File(translationsFolder, DEFAULT_FILE_NAME));

		File[] files = translationsFolder.listFiles((dir, name) -> name.endsWith(FILE_EXTENSION));
		if (files != null) {
			for (File file : files) {
				loadTranslationFile(plugin, file);
			}
		}

		GlobalTranslator.translator().addSource(currentStore);
	}

	/**
	 * Tags available in every translation, on top of the standard MiniMessage ones:
	 * {@code <prefix>}, {@code <translate:key>}, {@code <plugin:name|description|version|authors>} and
	 * {@code <platform:name|version>}.
	 */
	private static @NotNull TagResolver createGlobalTagResolver(ModUtilsPlugin plugin) {
		@SuppressWarnings("deprecation") // Paper deprecates it for getPluginMeta, which Bukkit does not have
		PluginDescriptionFile meta = plugin.getDescription();
		return TagResolver.builder()
				.resolver(TagResolver.standard())
				.tag("prefix", (args, context) -> Tag.selfClosingInserting(translatable("prefix")))
				.tag("translate", (args, context) ->
						Tag.selfClosingInserting(translatable(args.popOr("Missing argument for tag \"translate\"").value())))
				.tag("plugin", (args, context) -> {
					String property = args.popOr("Missing argument for tag \"plugin\"").lowerValue();
					String value = switch (property) {
						case "name" -> meta.getName();
						case "description" -> meta.getDescription();
						case "version" -> meta.getVersion();
						case "authors" -> String.join(", ", meta.getAuthors());
						default -> throw context.newException("Invalid argument \"" + property + "\"", args);
					};
					return Tag.selfClosingInserting(value != null ? Component.text(value) : translatable("meta.unknown"));
				})
				.tag("platform", (args, context) -> {
					String property = args.popOr("Missing argument for tag \"platform\"").lowerValue();
					String value = switch (property) {
						case "name" -> plugin.getServer().getName();
						case "version" -> plugin.getPlatform().minecraftVersion();
						default -> throw context.newException("Invalid argument \"" + property + "\"", args);
					};
					return Tag.selfClosingInserting(Component.text(value));
				})
				.build();
	}

	/**
	 * Loads a single locale file (e.g. en_US.yml) into the store.
	 * Every key is registered both with and without the plugin namespace prefix,
	 * so old (prefixed) and new (bare) lookups resolve to the same message.
	 */
	private static void loadTranslationFile(ModUtilsPlugin plugin, File file) {
		String fileName = file.getName();
		Locale locale = parseLocale(fileName);

		if (locale == null) {
			plugin.getLogger().warning("Skipping translation file with invalid locale name: " + fileName);
			return;
		}

		try {
			YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
			Set<String> registered = new HashSet<>();

			for (String key : config.getKeys(true)) {
				String value = null;
				String bareKey = key.startsWith(KEY_PREFIX) ? key.substring(KEY_PREFIX.length()) : key;

				if (config.isList(key)) {
					List<String> lines = config.getStringList(key);
					value = String.join("\n", lines);

					for (int i = 0; i < lines.size(); i++) {
						register(bareKey + "." + i, locale, lines.get(i), registered);
						register(KEY_PREFIX + bareKey + "." + i, locale, lines.get(i), registered);
					}
				} else if (config.isString(key)) {
					value = config.getString(key);
				}

				if (value == null) continue;

				register(bareKey, locale, value, registered);
				register(KEY_PREFIX + bareKey, locale, value, registered);
			}
		} catch (Exception e) {
			plugin.getLogger().log(Level.SEVERE, "Failed to load translation file: " + fileName, e);
		}
	}

	private static void register(String key, Locale locale, String value, Set<String> registered) {
		if (registered.add(key)) {
			currentStore.put(key, locale, value);
		}
	}

	/**
	 * Converts a file name like "en_US.yml" into a Locale. Returns null if it isn't a valid locale.
	 */
	private static @Nullable Locale parseLocale(String fileName) {
		String localeName = fileName.substring(0, fileName.length() - FILE_EXTENSION.length());
		Locale locale = Locale.forLanguageTag(localeName.replace('_', '-'));
		return locale.getLanguage().isEmpty() ? null : locale;
	}

	/**
	 * Makes sure translations/en_US.yml exists and holds every key of the one packed inside the jar. Missing keys are
	 * merged in with {@link LangFileMerger}, so edits and comments in the file on disk are kept.
	 */
	private static void syncDefaultTranslationFile(ModUtilsPlugin plugin, File target) {
		try (InputStream jarIn = plugin.getResource(TRANSLATIONS_FOLDER + "/" + DEFAULT_FILE_NAME)) {
			if (jarIn == null) {
				plugin.getLogger().severe("Could not find default resource inside jar: " + DEFAULT_FILE_NAME);
				return;
			}
			String reference = new String(jarIn.readAllBytes(), StandardCharsets.UTF_8);

			if (!target.exists()) {
				Files.writeString(target.toPath(), reference, StandardCharsets.UTF_8);
				return;
			}

			String current = Files.readString(target.toPath(), StandardCharsets.UTF_8);
			String merged = LangFileMerger.merge(current, reference);
			if (!merged.equals(current)) {
				Files.writeString(target.toPath(), merged, StandardCharsets.UTF_8);
				plugin.getLogger().info(
						"Successfully updated " + DEFAULT_FILE_NAME + " with new translation entries from the latest update."
				);
			}
		} catch (Exception e) {
			plugin.getLogger().log(Level.WARNING, "Failed to sync " + DEFAULT_FILE_NAME + " with the bundled defaults!", e);
		}
	}

	public static @NotNull TranslatableComponent translatable(@NotNull String key, @NotNull ComponentLike... args) {
		return Component.translatable(KEY_PREFIX + key, args);
	}

	public static @NotNull Component translatableManual(@NotNull Player player, @NotNull String key, @NotNull ComponentLike... args) {
		Component translation = GlobalTranslator.render(translatable(key, args), platform.locale(player));
		return Objects.requireNonNullElseGet(translation, () -> Component.text(KEY_PREFIX + key));
	}

	/**
	 * For viewers that are not a {@link Player} yet, e.g. a connection in the configuration phase.
	 */
	public static @NotNull Component translatableManual(@NotNull Locale locale, @NotNull String key, @NotNull ComponentLike... args) {
		Component translation = GlobalTranslator.render(translatable(key, args), locale);
		return Objects.requireNonNullElseGet(translation, () -> Component.text(KEY_PREFIX + key));
	}

	/**
	 * Translation source that renders its messages with MiniMessage. Adventure's own MiniMessageTranslationStore needs
	 * a recent Adventure and isn't there on older servers, so this does the same with the API every version has:
	 * messages are looked up by locale, then by language, then in {@link #DEFAULT_LOCALE}, and the component's
	 * arguments are available as {@code <arg:0>}, {@code <arg:1>}, ...
	 */
	private static final class Store implements Translator {
		private final MiniMessage miniMessage;
		private final Map<String, Map<Locale, String>> messages = new HashMap<>();

		private Store(MiniMessage miniMessage) {
			this.miniMessage = miniMessage;
		}

		boolean contains(String key) {
			return messages.containsKey(key);
		}

		void put(String key, Locale locale, String value) {
			messages.computeIfAbsent(key, k -> new HashMap<>()).put(locale, value);
		}

		@Override
		public @NotNull Key name() {
			return TRANSLATOR_KEY;
		}

		@Override
		public @Nullable MessageFormat translate(@NotNull String key, @NotNull Locale locale) {
			return null;
		}

		@Override
		public @Nullable Component translate(@NotNull TranslatableComponent component, @NotNull Locale locale) {
			Map<Locale, String> byLocale = messages.get(component.key());
			if (byLocale == null) return null;

			String raw = byLocale.get(locale);
			if (raw == null) raw = byLocale.get(Locale.forLanguageTag(locale.getLanguage()));
			if (raw == null) raw = byLocale.get(DEFAULT_LOCALE);
			if (raw == null) raw = byLocale.get(Locale.forLanguageTag(DEFAULT_LOCALE.getLanguage()));
			if (raw == null) return null;

			List<Component> args = new ArrayList<>();
			TagResolver.Builder named = TagResolver.builder();
			for (TranslationArgument argument : component.arguments()) {
				Component value = argument.asComponent();
				Optional<String> name = Argument.nameOf(value);
				if (name.isPresent()) {
					List<Component> inner = ((TranslatableComponent) value).arguments().stream()
							.map(TranslationArgument::asComponent).toList();
					named.resolver(Placeholder.component(name.get(), inner.isEmpty() ? Component.empty() : inner.getFirst()));
				} else {
					args.add(value);
				}
			}
			Component rendered = miniMessage.deserialize(raw, TagResolver.resolver(named.build(), TagResolver.resolver("arg", (queue, context) -> {
				int index = queue.popOr("Missing argument for tag \"arg\"").asInt()
						.orElseThrow(() -> context.newException("Argument for tag \"arg\" must be a number", queue));
				if (index < 0 || index >= args.size()) throw context.newException("No argument " + index, queue);
				return Tag.selfClosingInserting(args.get(index));
			})));
			// The arguments were put into the message after it was looked up, so translatable ones among them are
			// rendered here: the renderer only goes through the component it was given
			rendered = GlobalTranslator.render(rendered, locale);
			return Component.text().style(component.style()).append(rendered).append(component.children()).build();
		}
	}
}
