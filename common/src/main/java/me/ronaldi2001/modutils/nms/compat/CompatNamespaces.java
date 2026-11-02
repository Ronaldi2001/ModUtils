package me.ronaldi2001.modutils.nms.compat;

import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * The namespaces custom items ({@code modCompat.polymer.itemNamespace}) and synced recipes
 * ({@code modCompat.jei.recipeNamespace}) are shown under. Either setting can be {@code $itemNamespace} or
 * {@code $recipeNamespace} to use the other one's value; two that point at each other are treated as unset.
 */
public final class CompatNamespaces {
	public static final String DEFAULT_ITEM_NAMESPACE = "modcompat";

	private static final String ITEM_REF = "$itemNamespace";
	private static final String RECIPE_REF = "$recipeNamespace";

	private CompatNamespaces() {
	}

	/** @param section any section directly under {@code modCompat} */
	public static String itemNamespace(ConfigurationSection section) {
		String raw = read(section, "polymer.itemNamespace", DEFAULT_ITEM_NAMESPACE);
		if (raw.equals(RECIPE_REF)) raw = read(section, "jei.recipeNamespace", "");
		String cleaned = clean(raw);
		return cleaned.isEmpty() ? DEFAULT_ITEM_NAMESPACE : cleaned;
	}

	/** @return null to leave recipes under their own namespaces */
	public static @Nullable String recipeNamespace(ConfigurationSection section) {
		String raw = read(section, "jei.recipeNamespace", "");
		if (raw.equals(ITEM_REF)) raw = read(section, "polymer.itemNamespace", DEFAULT_ITEM_NAMESPACE);
		String cleaned = clean(raw);
		return cleaned.isEmpty() ? null : cleaned;
	}

	private static String read(ConfigurationSection section, String path, String fallback) {
		ConfigurationSection root = section.getParent() == null ? section : section.getParent();
		return root.getString(path, fallback).trim();
	}

	/** Identifier namespaces are lowercase letters, digits, and _ - . only; a reference left over is not a namespace. */
	private static String clean(String raw) {
		if (raw.equals(ITEM_REF) || raw.equals(RECIPE_REF)) return "";
		return raw.toLowerCase().replaceAll("[^a-z0-9_.-]+", "_");
	}
}
