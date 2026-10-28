package me.ronaldi2001.modutils.bukkit;

import me.ronaldi2001.modutils.platform.ModScheduler;
import me.ronaldi2001.modutils.platform.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/** Bukkit's scheduler: everything runs on the main thread, so an entity or a location needs no thread of its own. */
final class BukkitScheduler implements ModScheduler {
	private final Plugin plugin;

	BukkitScheduler(Plugin plugin) {
		this.plugin = plugin;
	}

	private static ScheduledTask wrap(BukkitTask task) {
		return task::cancel;
	}

	@Override
	public @Nullable ScheduledTask runOnEntity(Entity entity, Runnable task) {
		return wrap(this.plugin.getServer().getScheduler().runTask(this.plugin, task));
	}

	@Override
	public @Nullable ScheduledTask runOnEntityDelayed(Entity entity, Runnable task, long delayTicks) {
		return wrap(this.plugin.getServer().getScheduler().runTaskLater(this.plugin, task, delayTicks));
	}

	@Override
	public @Nullable ScheduledTask runOnEntityTimer(Entity entity, Runnable task, @Nullable Runnable retired, long delayTicks, long periodTicks) {
		return wrap(new BukkitRunnable() {
			@Override
			public void run() {
				// Bukkit does not stop a task when its entity goes away, as Folia's entity scheduler does
				if (!entity.isValid()) {
					this.cancel();
					if (retired != null) retired.run();
					return;
				}
				task.run();
			}
		}.runTaskTimer(this.plugin, delayTicks, periodTicks));
	}

	@Override
	public @Nullable ScheduledTask runAtLocation(Location location, Runnable task) {
		return wrap(this.plugin.getServer().getScheduler().runTask(this.plugin, task));
	}

	@Override
	public @Nullable ScheduledTask runAsyncDelayed(Runnable task, long delay, TimeUnit unit) {
		long ticks = Math.max(0, unit.toMillis(delay) / 50);
		return wrap(this.plugin.getServer().getScheduler().runTaskLaterAsynchronously(this.plugin, task, ticks));
	}
}
