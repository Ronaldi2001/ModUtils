package me.ronaldi2001.modutils.nms.compat.trashslot;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.ronaldi2001.modutils.ModUtilsPlugin;
import me.ronaldi2001.modutils.modCompat.api.ModCompat;
import me.ronaldi2001.modutils.pluginHooks.CustomItemSource;
import me.ronaldi2001.modutils.pluginHooks.ItemNames;
import me.ronaldi2001.modutils.translations.Argument;
import me.ronaldi2001.modutils.translations.TransUtils;
import net.kyori.adventure.text.Component;
import me.ronaldi2001.modutils.nms.ItemWriterCache;
import me.ronaldi2001.modutils.nms.Nms;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jspecify.annotations.NonNull;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * TrashSlot: the server keeps the one stack each player has in their trash slot, so it can be taken back, and does the
 * deleting that the client asks for. Without a server side the mod still deletes on the client, but cannot undo it.
 * <p>
 * Channels (the same on every TrashSlot from 1.20.6 to 26.3, Fabric and NeoForge): {@code trashslot:delete_from_slot}
 * (slot number, delete all) and {@code trashslot:trash_slot_click} (the stack in hand, right click), both from the
 * client, and {@code trashslot:trash_slot_content} (the stack in the trash slot) from the server. The last one is also
 * how the client learns that the server has the mod, so it is sent on join.
 * <p>
 * Like TrashSlot itself, the trash is empty after logging in. It is kept in memory only.
 * <p>
 * Deleting is limited to the player's own inventory and the crafting grid: a client could otherwise aim it at the
 * slots of a plugin's GUI (shops, backpacks, ...), where this plugin has no way of asking the owner for permission.
 */
public class TrashSlotCompat extends ModCompat implements PluginMessageListener {
	private static final String DELETE_FROM_SLOT = "trashslot:delete_from_slot";
	private static final String TRASH_SLOT_CLICK = "trashslot:trash_slot_click";
	private static final String TRASH_SLOT_CONTENT = "trashslot:trash_slot_content";
	private static final String[] INCOMING = {DELETE_FROM_SLOT, TRASH_SLOT_CLICK};
	/** The slot number that stands for the trash slot itself. */
	private static final int TRASH_SLOT = -1;
	/** Ticks to wait before the first content goes out, for a client that registers its channels after joining. */
	private static final long JOIN_DELAY_TICKS = 40L;

	private final Map<UUID, ItemStack> trash = new ConcurrentHashMap<>();
	private final ItemWriterCache itemWriters;
	/** Lower case: item ids, and {@code prefix:id} for the items of ItemsAdder, Nexo, Oraxen and CraftEngine. */
	private final Set<String> denyList = new HashSet<>();
	/** Whether the deny list names a custom item, which takes a look at the plugins' items to check against. */
	private boolean denyCustomItems;
	private boolean debug;

	public TrashSlotCompat(ModUtilsPlugin plugin) {
		super(plugin);
		this.itemWriters = new ItemWriterCache(plugin);
	}

	@Override
	public String getConfigKey() {
		return "trashSlot";
	}

	@Override
	public String getName() {
		return "TrashSlot";
	}

	@Override
	public void onEnable(ConfigurationSection section) {
		this.debug = section.getBoolean("debug", false);
		this.denyList.clear();
		this.denyCustomItems = false;
		for (String id : section.getStringList("deletionDenyList")) {
			String lower = id.trim().toLowerCase(Locale.ROOT);
			this.denyList.add(lower);
			// Plugins may load after this, so anything that is not a vanilla id counts
			if (!lower.startsWith("minecraft:")) this.denyCustomItems = true;
		}

		Messenger messenger = this.plugin.getServer().getMessenger();
		for (String channel : INCOMING) messenger.registerIncomingPluginChannel(this.plugin, channel, this);
		messenger.registerOutgoingPluginChannel(this.plugin, TRASH_SLOT_CONTENT);

		for (Player player : this.plugin.getServer().getOnlinePlayers()) {
			if (this.detect(player)) this.sendContent(player);
		}
	}

	@Override
	public void onDisable() {
		Messenger messenger = this.plugin.getServer().getMessenger();
		for (String channel : INCOMING) messenger.unregisterIncomingPluginChannel(this.plugin, channel, this);
		messenger.unregisterOutgoingPluginChannel(this.plugin, TRASH_SLOT_CONTENT);
		this.trash.clear();
		this.itemWriters.clear();
	}

	@Override
	public boolean detect(Player player) {
		return player.getListeningPluginChannels().contains(TRASH_SLOT_CONTENT);
	}

	@EventHandler
	public void onRegisterChannel(PlayerRegisterChannelEvent event) {
		if (!TRASH_SLOT_CONTENT.equals(event.getChannel())) return;

		this.debug(event.getPlayer().getName() + " registered " + TRASH_SLOT_CONTENT);
		this.sendContent(event.getPlayer());
	}

	/** For a client that registered its channels without an event, and so would not know the server has the mod. */
	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		this.trash.remove(player.getUniqueId());
		this.plugin.getScheduler().runOnEntityDelayed(player, () -> this.sendContent(player), JOIN_DELAY_TICKS);
	}

	@EventHandler
	public void onRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		this.plugin.getScheduler().runOnEntityDelayed(player, () -> this.sendContent(player), 1L);
	}

	/** TrashSlot asks the server for nothing when a container opens; it expects the content to arrive. */
	@EventHandler
	public void onInventoryOpen(InventoryOpenEvent event) {
		if (event.getPlayer() instanceof Player player) this.sendContent(player);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		UUID id = event.getPlayer().getUniqueId();
		this.trash.remove(id);
		this.itemWriters.remove(id);
	}

	@Override
	public void onPluginMessageReceived(@NonNull String channel, @NonNull Player player, byte @NonNull [] message) {
		if (this.plugin.getBedrockHook().isBedrock(player)) return;
		// Only players whose client has the mod
		if (!this.detect(player)) return;

		ByteBuf raw = Unpooled.wrappedBuffer(message);
		try {
			FriendlyByteBuf in = new FriendlyByteBuf(raw);
			ServerPlayer handle = ((CraftPlayer) player).getHandle();
			if (channel.equals(DELETE_FROM_SLOT)) {
				int slot = in.readVarInt();
				boolean deleteAll = in.readBoolean();
				this.debug(player.getName() + " deletes from slot " + slot + (deleteAll ? " (all)" : ""));
				this.plugin.getScheduler().runOnEntity(player, () -> this.run(player, () -> this.deleteFromSlot(player, handle, slot, deleteAll)));
			} else if (channel.equals(TRASH_SLOT_CLICK)) {
				// The stack in hand starts with its count, which is 0 for nothing. It is the only part read: the rest
				// is in the layout of the client's version, and the real stack in hand is the server's to know.
				boolean handEmpty = in.readVarInt() <= 0;
				// The click is the last byte, after the stack in the layout of the client's version
				boolean rightClick = message[message.length - 1] != 0;
				this.debug(player.getName() + " clicks the trash slot" + (rightClick ? " (right)" : ""));
				this.plugin.getScheduler().runOnEntity(player, () -> this.run(player, () -> this.click(player, handle, handEmpty, rightClick)));
			}
		} catch (Exception e) {
			this.plugin.getLogger().log(Level.WARNING, "Bad TrashSlot packet " + channel + " from " + player.getName(), e);
		} finally {
			raw.release();
		}
	}

	private void run(Player player, Runnable action) {
		try {
			action.run();
		} catch (Exception e) {
			this.plugin.getLogger().log(Level.WARNING, "TrashSlot request of " + player.getName() + " failed", e);
		}
	}

	private void deleteFromSlot(Player player, ServerPlayer handle, int slotNumber, boolean deleteAll) {
		if (handle.isSpectator()) return;

		UUID id = player.getUniqueId();
		if (slotNumber == TRASH_SLOT) {
			this.trash.remove(id);
			this.sendContent(player);
			return;
		}

		AbstractContainerMenu menu = handle.containerMenu;
		if (!menu.getCarried().isEmpty()) return;
		if (slotNumber < 0 || slotNumber >= menu.slots.size()) return;

		Slot slot = menu.slots.get(slotNumber);
		ItemStack target = slot.getItem();
		if (this.denied(target)) this.tellDenied(player, target);

		// The client has already taken the items out of its own view, so whatever is refused has to be sent back
		boolean refused = true;
		if (this.take(handle, slot)) {
			refused = false;
			if (deleteAll) {
				ItemStack deleted = this.trash.get(id);
				for (Slot other : menu.slots) {
					ItemStack stack = other.getItem();
					if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, deleted)) continue;
					if (!this.take(handle, other)) refused = true;
				}
			}
			menu.broadcastChanges();
		}
		if (refused) menu.sendAllDataToRemote();
		this.sendContent(player);
	}

	/** Moves what is in the slot into the trash. Returns whether anything was. */
	private boolean take(ServerPlayer player, Slot slot) {
		if (!this.canDeleteFrom(player, slot)) return false;

		ItemStack stack = slot.getItem();
		if (stack.isEmpty() || this.denied(stack) || !slot.mayPickup(player)) return false;

		this.trash.put(player.getUUID(), stack.copy());
		slot.set(ItemStack.EMPTY);
		return true;
	}

	private boolean canDeleteFrom(ServerPlayer player, Slot slot) {
		if (slot instanceof ResultSlot || slot.isFake()) return false;
		if (slot.container == player.getInventory()) return true;
		AbstractContainerMenu menu = player.containerMenu;
		return menu instanceof InventoryMenu || menu instanceof CraftingMenu;
	}

	/** The stack in hand goes into the trash (or one of it), or the trash comes into the hand. */
	private void click(Player player, ServerPlayer handle, boolean handEmpty, boolean rightClick) {
		if (handle.isSpectator()) return;

		UUID id = player.getUniqueId();
		AbstractContainerMenu menu = handle.containerMenu;
		ItemStack carried = menu.getCarried();
		// A hand the client sees differently than the server does, or an item that may not go in: leave it and put the client right
		if (carried.isEmpty() != handEmpty || this.denied(carried)) {
			if (this.denied(carried)) this.tellDenied(player, carried);
			menu.sendAllDataToRemote();
			// The trash slot is the client's own, so what it shows has to be corrected too
			this.sendContent(player);
			return;
		}

		ItemStack trashed = this.trash.getOrDefault(id, ItemStack.EMPTY);
		if (carried.isEmpty()) {
			ItemStack inHand = rightClick ? trashed.split(1) : trashed;
			menu.setCarried(inHand);
			this.store(id, rightClick ? trashed : ItemStack.EMPTY);
		} else {
			ItemStack toTrash = rightClick ? carried.split(1) : carried;
			this.store(id, toTrash);
			menu.setCarried(rightClick ? carried : ItemStack.EMPTY);
		}
	}

	/** Tells the player, in their language, that the item may not be deleted. */
	private void tellDenied(Player player, ItemStack stack) {
		org.bukkit.inventory.ItemStack bukkit = CraftItemStack.asBukkitCopy(stack);
		Component name = ItemNames.of(bukkit);
		if (name == null) name = Component.translatable(stack.getItem().getDescriptionId());
		this.plugin.getPlatform().send(player, TransUtils.translatableManual(player, "trashSlot.denied", Argument.component("item", name)));
	}

	private void store(UUID id, ItemStack stack) {
		if (stack.isEmpty()) this.trash.remove(id);
		else this.trash.put(id, stack);
	}

	/**
	 * Whether the stack is on the deny list, by its vanilla id or, for a custom item, by the id its plugin gives it
	 * ({@code nexo:ruby}, {@code itemsadder:ns:id}, ...). A custom item is also denied through the vanilla item it is
	 * made of.
	 */
	private boolean denied(ItemStack stack) {
		if (stack.isEmpty() || this.denyList.isEmpty()) return false;
		if (this.denyList.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) return true;
		if (!this.denyCustomItems) return false;

		org.bukkit.inventory.ItemStack bukkit = CraftItemStack.asBukkitCopy(stack);
		for (CustomItemSource source : this.plugin.getItemSources()) {
			String id = source.itemId(bukkit);
			if (id != null && this.denyList.contains((source.prefix() + ":" + id).toLowerCase(Locale.ROOT))) return true;
		}
		return false;
	}

	private void sendContent(Player player) {
		if (!player.isOnline() || this.plugin.getBedrockHook().isBedrock(player)) return;
		if (!this.detect(player)) return;

		ServerPlayer handle = ((CraftPlayer) player).getHandle();
		ItemStack stack = this.trash.getOrDefault(player.getUniqueId(), ItemStack.EMPTY);
		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), Nms.level(handle).registryAccess());
		try {
			if (stack.isEmpty()) out.writeVarInt(0);
			else this.itemWriters.forPlayer(player).write(out, stack);
			byte[] bytes = new byte[out.readableBytes()];
			out.readBytes(bytes);
			player.sendPluginMessage(this.plugin, TRASH_SLOT_CONTENT, bytes);
		} finally {
			out.release();
		}
		this.debug("Sent the trash slot content (" + (stack.isEmpty() ? "empty" : stack.getCount() + "x " + stack.getItem()) + ") to " + player.getName());
	}

	private void debug(String message) {
		if (this.debug) this.plugin.getLogger().info("[TrashSlot] " + message);
	}
}
