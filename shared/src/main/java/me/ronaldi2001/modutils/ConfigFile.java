package me.ronaldi2001.modutils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Changes one value in config.yml by editing its line in place. Bukkit's own save rewrites the whole file and can move or
 * drop comments and blank lines, and this file is mostly comments.
 */
final class ConfigFile {
	private static final Pattern KEY_LINE = Pattern.compile("^( *)([A-Za-z0-9_.\\-]+):(.*)$");
	private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./:+\\-]+");

	private ConfigFile() {
	}

	/**
	 * @param path  the setting, e.g. {@code jade.features.furnace}
	 * @param value the new value as it should appear in the file
	 * @return false if the file has no line for that setting (it is only there as a default), so nothing was changed
	 */
	static boolean set(Path file, String path, Object value) throws IOException {
		String text = Files.readString(file, StandardCharsets.UTF_8);
		String newline = text.contains("\r\n") ? "\r\n" : "\n";
		String[] lines = text.split("\r?\n", -1);

		String replacement = format(value);
		List<String> keys = new ArrayList<>();
		List<Integer> indents = new ArrayList<>();
		boolean changed = false;
		for (int i = 0; i < lines.length && !changed; i++) {
			Matcher match = KEY_LINE.matcher(lines[i]);
			if (!match.matches()) continue;

			int indent = match.group(1).length();
			while (!indents.isEmpty() && indents.getLast() >= indent) {
				indents.removeLast();
				keys.removeLast();
			}
			String key = match.group(2);
			keys.add(key);
			indents.add(indent);
			if (!String.join(".", keys).equals(path)) continue;

			String rest = match.group(3);
			int comment = commentStart(rest);
			String beforeComment = comment < 0 ? rest : rest.substring(0, comment);
			String current = beforeComment.strip();
			if (current.isEmpty() || current.startsWith("[") || current.startsWith("{")) return false;

			int lead = beforeComment.length() - beforeComment.stripLeading().length();
			int tail = beforeComment.length() - beforeComment.stripTrailing().length();
			lines[i] = match.group(1) + key + ":" + (lead > 0 ? beforeComment.substring(0, lead) : " ") + replacement
					+ (comment < 0 ? "" : beforeComment.substring(beforeComment.length() - tail) + rest.substring(comment));
			changed = true;
		}
		if (!changed) return false;

		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, String.join(newline, lines), StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		return true;
	}

	/** Where a trailing {@code # comment} starts, or -1. A # inside quotes, or one not after a space, is part of the value. */
	private static int commentStart(String rest) {
		char quote = 0;
		for (int i = 0; i < rest.length(); i++) {
			char c = rest.charAt(i);
			if (quote != 0) {
				if (c == quote) quote = 0;
			} else if (c == '"' || c == '\'') {
				quote = c;
			} else if (c == '#' && (i == 0 || Character.isWhitespace(rest.charAt(i - 1)))) {
				return i;
			}
		}
		return -1;
	}

	private static String format(Object value) {
		if (!(value instanceof String text)) return String.valueOf(value);
		boolean needsQuotes = !PLAIN.matcher(text).matches() || text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")
				|| text.equalsIgnoreCase("null") || text.equalsIgnoreCase("yes") || text.equalsIgnoreCase("no")
				|| text.matches("[-+]?[0-9.]+");
		return needsQuotes ? "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : text;
	}
}
