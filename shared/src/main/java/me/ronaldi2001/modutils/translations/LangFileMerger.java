package me.ronaldi2001.modutils.translations;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inserts keys that exist in a reference YAML text but are missing from a target YAML text, placing each one under
 * its parent key in the same order as the reference. The target is edited as plain text, so its existing content,
 * comments and formatting are kept exactly as they were.
 * <p>
 * Used for the lang files and config.yml. Only the simple structure they use is understood (nested maps with string values, including
 * block scalars). Keys that can't be placed, for example because the target has a string where the reference has
 * a map, are left out.
 */
public final class LangFileMerger {
	private static final Pattern KEY = Pattern.compile("^(\\s*)(\"[^\"]*\"|'[^']*'|[^\\s#:][^:]*?)\\s*:(?:\\s+(.*))?$");
	private static final Pattern BLOCK_SCALAR = Pattern.compile("^[|>][+-]?\\d*\\s*(#.*)?$");

	private record Node(@NotNull List<String> path, int indent, int start, int end, boolean leaf) {
	}

	private LangFileMerger() {
	}

	public static @NotNull String merge(@NotNull String target, @NotNull String reference) {
		String separator = target.contains("\r\n") ? "\r\n" : "\n";
		boolean trailingNewline = target.endsWith("\n");
		List<String> lines = split(target);
		List<String> referenceLines = split(reference);
		List<Node> referenceNodes = parse(referenceLines);
		Map<List<String>, Node> referenceByPath = byPath(referenceNodes);
		boolean changed = true;
		while (changed) {
			changed = false;
			Map<List<String>, Node> targetByPath = byPath(parse(lines));
			for (Node node : referenceNodes) {
				if (targetByPath.containsKey(node.path())) continue;
				List<String> parentPath = node.path().subList(0, node.path().size() - 1);
				Node parent = parentPath.isEmpty() ? null : targetByPath.get(parentPath);
				if (!parentPath.isEmpty() && (parent == null || parent.leaf())) continue;
				int childIndent = parent == null ? node.indent() : parent.indent() + (node.indent() - referenceByPath.get(parentPath).indent());
				int insertAt = insertionPoint(lines, node, parent, parentPath, referenceNodes, targetByPath);
				List<String> block = createLines(referenceLines, node, childIndent);
				// Keep the spacing the reference has around the key, without doubling blank lines already there
				int first = leadingStart(referenceLines, node.start());
				int last = node.leaf() ? node.end() : node.start() + 1;
				if (first > 0 && referenceLines.get(first - 1).isBlank() && insertAt > 0 && !lines.get(insertAt - 1).isBlank()) block.addFirst("");
				if (node.leaf() && last < referenceLines.size() && referenceLines.get(last).isBlank()
						&& insertAt < lines.size() && !lines.get(insertAt).isBlank()) block.add("");
				lines.addAll(insertAt, block);
				changed = true;
				break;
			}
		}
		return String.join(separator, lines) + (trailingNewline ? separator : "");
	}

	/**
	 * Where a missing key goes: after the sibling before it in the reference, or else before the sibling after it, so the
	 * order matches the reference. With no sibling in the target it goes at the end of its parent.
	 */
	private static int insertionPoint(@NotNull List<String> lines, @NotNull Node node, Node parent, @NotNull List<String> parentPath,
									  @NotNull List<Node> referenceNodes, @NotNull Map<List<String>, Node> targetByPath) {
		List<Node> siblings = referenceNodes.stream()
				.filter(n -> n.path().size() == node.path().size() && n.path().subList(0, n.path().size() - 1).equals(parentPath))
				.toList();
		int index = siblings.indexOf(node);
		for (int i = index - 1; i >= 0; i--) {
			Node existing = targetByPath.get(siblings.get(i).path());
			if (existing != null) return existing.end();
		}
		for (int i = index + 1; i < siblings.size(); i++) {
			Node existing = targetByPath.get(siblings.get(i).path());
			if (existing != null) return leadingStart(lines, existing.start());
		}
		return parent == null ? lastContentLine(lines) + 1 : parent.end();
	}

	/** The first line of the comments directly above the key at {@code start}, or {@code start} if it has none. */
	private static int leadingStart(@NotNull List<String> lines, int start) {
		while (start > 0 && lines.get(start - 1).trim().startsWith("#")) start--;
		return start;
	}

	private static @NotNull List<String> split(@NotNull String text) {
		List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
		if (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
		return lines;
	}

	private static @NotNull Map<List<String>, Node> byPath(@NotNull List<Node> nodes) {
		Map<List<String>, Node> map = new HashMap<>();
		nodes.forEach(n -> map.putIfAbsent(n.path(), n));
		return map;
	}

	private static @NotNull List<String> createLines(@NotNull List<String> referenceLines, @NotNull Node node, int childIndent) {
		int first = leadingStart(referenceLines, node.start());
		int last = node.leaf() ? node.end() : node.start() + 1;
		int shift = childIndent - node.indent();
		List<String> result = new ArrayList<>();
		for (String line : referenceLines.subList(first, last)) {
			if (line.isBlank()) result.add("");
			else if (shift >= 0) result.add(" ".repeat(shift) + line);
			else result.add(line.substring(Math.min(-shift, indentOf(line))));
		}
		return result;
	}

	private static @NotNull List<Node> parse(@NotNull List<String> lines) {
		List<Node> nodes = new ArrayList<>();
		List<Integer> stackIndents = new ArrayList<>();
		List<String> stackKeys = new ArrayList<>();
		int blockIndent = -1;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.isBlank() || line.trim().startsWith("#")) continue;
			int indent = indentOf(line);
			if (blockIndent >= 0) {
				if (indent > blockIndent) continue;
				blockIndent = -1;
			}
			Matcher matcher = KEY.matcher(line);
			if (!matcher.matches()) continue;
			while (!stackIndents.isEmpty() && stackIndents.getLast() >= indent) {
				stackIndents.removeLast();
				stackKeys.removeLast();
			}
			stackIndents.add(indent);
			stackKeys.add(unquote(matcher.group(2)));
			String value = matcher.group(3) == null ? "" : matcher.group(3).trim();
			if (BLOCK_SCALAR.matcher(value).matches()) blockIndent = indent;
			int end = subtreeEnd(lines, i, indent);
			// A key holding a list is copied whole, its items are not keys of their own
			boolean leaf = !value.isEmpty() && !value.startsWith("#") || holdsList(lines, i, end);
			nodes.add(new Node(List.copyOf(stackKeys), indent, i, end, leaf));
		}
		return nodes;
	}

	/**
	 * The line after the last content line (blank and comment-only lines excluded) that belongs to the key at {@code start}.
	 */
	private static int subtreeEnd(@NotNull List<String> lines, int start, int indent) {
		int lastContent = start;
		for (int j = start + 1; j < lines.size(); j++) {
			String line = lines.get(j);
			if (line.isBlank() || line.trim().startsWith("#")) continue;
			if (indentOf(line) <= indent) break;
			lastContent = j;
		}
		return lastContent + 1;
	}

	/** Whether the first content line under the key at {@code start} is a list item. */
	private static boolean holdsList(@NotNull List<String> lines, int start, int end) {
		for (int j = start + 1; j < end; j++) {
			String line = lines.get(j).trim();
			if (line.isEmpty() || line.startsWith("#")) continue;
			return line.equals("-") || line.startsWith("- ");
		}
		return false;
	}

	private static int lastContentLine(@NotNull List<String> lines) {
		for (int i = lines.size() - 1; i >= 0; i--) if (!lines.get(i).isBlank()) return i;
		return -1;
	}

	private static int indentOf(@NotNull String line) {
		int i = 0;
		while (i < line.length() && line.charAt(i) == ' ') i++;
		return i;
	}

	private static @NotNull String unquote(@NotNull String key) {
		if (key.length() >= 2 && (key.startsWith("\"") && key.endsWith("\"") || key.startsWith("'") && key.endsWith("'")))
			return key.substring(1, key.length() - 1);
		return key;
	}
}