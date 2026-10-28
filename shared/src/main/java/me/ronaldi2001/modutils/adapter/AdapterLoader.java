package me.ronaldi2001.modutils.adapter;

import me.ronaldi2001.modutils.platform.Platform;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the version adapter that matches the running server. Adapters are listed newest first; each one covers the
 * Minecraft versions from its own up to just before the next newer one, so a server newer than every adapter gets the
 * newest (it may still work).
 */
public final class AdapterLoader {
	private static final String PACKAGE = "me.ronaldi2001.modutils.nms.";

	/** Adapter id -> first Minecraft version it supports, newest first. */
	private static final Map<String, int[]> ADAPTERS = new LinkedHashMap<>();

	static {
		ADAPTERS.put("v26_3", new int[]{26, 3, 0});
		ADAPTERS.put("v26_2", new int[]{26, 2, 0});
		ADAPTERS.put("v26_1", new int[]{26, 1, 0});
		ADAPTERS.put("v1_21_11", new int[]{1, 21, 11});
		ADAPTERS.put("v1_21_10", new int[]{1, 21, 9});
		ADAPTERS.put("v1_21_8", new int[]{1, 21, 7});
		ADAPTERS.put("v1_21_6", new int[]{1, 21, 6});
		ADAPTERS.put("v1_21_5", new int[]{1, 21, 5});
		ADAPTERS.put("v1_21_4", new int[]{1, 21, 4});
		ADAPTERS.put("v1_21_3", new int[]{1, 21, 2});
		ADAPTERS.put("v1_21_1", new int[]{1, 21, 0});
		ADAPTERS.put("v1_20_6", new int[]{1, 20, 5});
	}

	private AdapterLoader() {
	}

	public record Loaded(String id, String minecraftVersion, VersionAdapter adapter) {
	}

	/**
	 * @param platform the adapters of the platform are the ones in its own package (see {@link Platform#id()})
	 * @return null if the server is older than the oldest adapter
	 */
	public static Loaded load(ClassLoader loader, Platform platform) throws ReflectiveOperationException {
		String version = platform.minecraftVersion();
		int[] current = parse(version);
		for (Map.Entry<String, int[]> entry : ADAPTERS.entrySet()) {
			if (compare(current, entry.getValue()) >= 0) {
				Class<?> type = Class.forName(PACKAGE + platform.id() + "." + entry.getKey() + ".NmsAdapter", true, loader);
				return new Loaded(entry.getKey(), version, (VersionAdapter) type.getDeclaredConstructor().newInstance());
			}
		}
		return null;
	}

	public static String oldestSupported() {
		int[] oldest = null;
		for (int[] v : ADAPTERS.values()) oldest = v;
		return oldest == null ? "?" : format(oldest);
	}

	/** The newest version that has an adapter of its own; newer servers get that one. */
	public static String newestSupported() {
		return ADAPTERS.isEmpty() ? "?" : format(ADAPTERS.values().iterator().next());
	}

	/** 1,21,11 -> "1.21.11"; 26,3,0 -> "26.3" */
	private static String format(int[] version) {
		return version[0] + "." + version[1] + (version[2] == 0 ? "" : "." + version[2]);
	}

	/** "1.21.11" -> 1,21,11; "26.1.2" -> 26,1,2; "26.2" -> 26,2,0 */
	static int[] parse(String version) {
		Matcher m = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(version);
		if (!m.find()) return new int[]{0, 0, 0};
		return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
				m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
	}

	private static int compare(int[] a, int[] b) {
		for (int i = 0; i < 3; i++) {
			if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
		}
		return 0;
	}
}
