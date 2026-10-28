package me.ronaldi2001.modutils.nms.compat.top;

import io.netty.buffer.Unpooled;
import me.ronaldi2001.modutils.nms.compat.jade.ItemWriter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What The One Probe shows for a block or entity: a tree of elements, written the way TOP's {@code ProbeInfo} is (the
 * layout is the same in every TOP version from 1.20.6 to 26.2).
 * <p>
 * The root is a vertical panel. A panel is its children (a count, then an element id and the element's own bytes each),
 * followed by its layout. Only the element types the providers use are here.
 */
public final class ProbeInfo {
	private static final String NAMESPACE = "theoneprobe";
	private static final Identifier TEXT = id("text");
	private static final Identifier ITEM = id("item");
	private static final Identifier PROGRESS = id("progress");
	private static final Identifier HORIZONTAL = id("hor");
	private static final Identifier VERTICAL = id("ver");
	private static final Identifier ITEM_LABEL = id("itemlabel");

	/** {@code ElementAlignment}, written as its ordinal. */
	public static final int ALIGN_TOPLEFT = 0;
	public static final int ALIGN_CENTER = 1;

	/** The size of an item icon. */
	private static final int ITEM_SIZE = 16;
	private static final int BAR_WIDTH = 100;
	private static final int BAR_HEIGHT = 12;

	private final Panel root = new Panel(VERTICAL, 2, ALIGN_TOPLEFT);

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(NAMESPACE, path);
	}

	public Panel root() {
		return this.root;
	}

	/** Writes the optional-ProbeInfo wrapper, as {@code ProbeInfo.STREAM_CODEC} does. */
	public void write(RegistryFriendlyByteBuf buf, ItemWriter items) {
		buf.writeBoolean(true);
		this.root.writePanel(buf, items);
	}

	public interface Element {
		Identifier id();

		void write(RegistryFriendlyByteBuf buf, ItemWriter items);
	}

	/** A vertical or horizontal group of elements. */
	public static final class Panel implements Element {
		private final Identifier id;
		private final int spacing;
		private final int alignment;
		private final List<Element> children = new ArrayList<>();

		private Panel(Identifier id, int spacing, int alignment) {
			this.id = id;
			this.spacing = spacing;
			this.alignment = alignment;
		}

		@Override
		public Identifier id() {
			return this.id;
		}

		public boolean isEmpty() {
			return this.children.isEmpty();
		}

		private Panel add(Element element) {
			this.children.add(element);
			return this;
		}

		/** A row. Its elements sit next to each other. */
		public Panel horizontal() {
			return this.horizontal(ALIGN_TOPLEFT);
		}

		public Panel horizontal(int alignment) {
			Panel row = new Panel(HORIZONTAL, -1, alignment);
			this.children.add(row);
			return row;
		}

		public Panel vertical() {
			Panel column = new Panel(VERTICAL, -1, ALIGN_TOPLEFT);
			this.children.add(column);
			return column;
		}

		public Panel text(Component text) {
			return this.add(new Text(text));
		}

		/** The stack drawn as an icon, with its count. An empty stack adds nothing. */
		public Panel item(ItemStack stack) {
			return stack.isEmpty() ? this : this.add(new Item(stack));
		}

		/**
		 * The name of the stack, which the client works out from the stack itself.
		 */
		public void itemLabel(ItemStack stack) {
			if (!stack.isEmpty()) {
				this.add(new ItemLabel(stack));
			}
		}

		public void progress(long current, long max, Progress style) {
			this.add(new Bar(current, max, style));
		}

		@Override
		public void write(RegistryFriendlyByteBuf buf, ItemWriter items) {
			this.writePanel(buf, items);
		}

		void writePanel(RegistryFriendlyByteBuf buf, ItemWriter items) {
			buf.writeVarInt(this.children.size());
			for (Element child : this.children) {
				Identifier.STREAM_CODEC.encode(buf, child.id());
				child.write(buf, items);
			}
			buf.writeVarInt(this.alignment);
			buf.writeBoolean(false); // no border color
			buf.writeInt(this.spacing);
			buf.writeInt(0).writeInt(0).writeInt(0).writeInt(0); // padding: top, bottom, left, right
		}
	}

	private record Text(Component text) implements Element {
		@Override
		public Identifier id() {
			return TEXT;
		}

		@Override
		public void write(RegistryFriendlyByteBuf buf, ItemWriter items) {
			ComponentSerialization.STREAM_CODEC.encode(buf, this.text);
			buf.writeVarInt(ALIGN_TOPLEFT);
			buf.writeInt(0).writeInt(0).writeInt(0).writeInt(0); // padding
			buf.writeBoolean(false); // width
			buf.writeBoolean(false); // height
			buf.writeBoolean(false); // legacy
		}
	}

	private record Item(ItemStack stack) implements Element {
		@Override
		public Identifier id() {
			return ITEM;
		}

		@Override
		public void write(RegistryFriendlyByteBuf buf, ItemWriter items) {
			writeStack(buf, items, this.stack);
			buf.writeInt(ITEM_SIZE).writeInt(ITEM_SIZE);
		}
	}

	private record ItemLabel(ItemStack stack) implements Element {
		@Override
		public Identifier id() {
			return ITEM_LABEL;
		}

		@Override
		public void write(RegistryFriendlyByteBuf buf, ItemWriter items) {
			writeStack(buf, items, this.stack);
		}
	}

	private record Bar(long current, long max, Progress style) implements Element {
		@Override
		public Identifier id() {
			return PROGRESS;
		}

		@Override
		public void write(RegistryFriendlyByteBuf buf, ItemWriter items) {
			buf.writeLong(this.current).writeLong(this.max);
			buf.writeInt(BAR_WIDTH).writeInt(BAR_HEIGHT);
			ComponentSerialization.STREAM_CODEC.encode(buf, this.style.prefix);
			ComponentSerialization.STREAM_CODEC.encode(buf, this.style.suffix);
			buf.writeInt(0xffffffff); // border
			buf.writeInt(this.style.filledColor).writeInt(this.style.filledColor); // filled, alternate filled
			buf.writeInt(this.style.backgroundColor);
			buf.writeBoolean(true); // show the numbers
			buf.writeByte(0); // number format: full
			buf.writeBoolean(false); // life bar
			buf.writeBoolean(false); // armor bar
			buf.writeVarInt(ALIGN_TOPLEFT);
		}
	}

	/** A progress bar's look. The defaults are TOP's own. */
	public static final class Progress {
		private Component prefix = Component.empty();
		private Component suffix = Component.empty();
		private int filledColor = 0xffaaaaaa;
		private int backgroundColor = 0xff000000;

		public static Progress create() {
			return new Progress();
		}

		public Progress prefix(Component prefix) {
			this.prefix = prefix;
			return this;
		}

		public Progress suffix(Component suffix) {
			this.suffix = suffix;
			return this;
		}

		public Progress filled(int color) {
			this.filledColor = color;
			return this;
		}

		public Progress background(int color) {
			this.backgroundColor = color;
			return this;
		}
	}

	/**
	 * TOP's {@code NetworkTools.writeItemStack}, behind a flag: the stack's usual layout and then its count as an int (a
	 * stack can be larger than 64). A stack the client cannot have (an item it does not know) is written as no item at
	 * all, which the flag tells.
	 */
	static void writeStack(RegistryFriendlyByteBuf buf, ItemWriter items, ItemStack stack) {
		RegistryFriendlyByteBuf scratch = new RegistryFriendlyByteBuf(Unpooled.buffer(), buf.registryAccess());
		try {
			items.write(scratch, stack);
			if (scratch.getByte(scratch.readerIndex()) == 0) { // the writer wrote it as empty
				buf.writeBoolean(false);
				return;
			}
			buf.writeBoolean(true);
			buf.writeBytes(scratch);
			buf.writeInt(stack.getCount());
		} finally {
			scratch.release();
		}
	}
}
