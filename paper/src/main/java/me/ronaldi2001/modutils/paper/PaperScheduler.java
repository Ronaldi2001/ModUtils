package me.ronaldi2001.modutils.paper;

import me.ronaldi2001.modutils.platform.ModScheduler;
import me.ronaldi2001.modutils.platform.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/** Paper's and Folia's schedulers: the entity scheduler for entities, the region scheduler for places. */
final class PaperScheduler implements ModScheduler {
	private final Plugin plugin;

	PaperScheduler(Plugin plugin) {
		this.plugin = plugin;
	}

	private static @Nullable ScheduledTask wrap(io.papermc.paper.threadedregions.scheduler.@Nullable ScheduledTask task) {
		return task == null ? null : task::cancel;
	}

	@Override
	public @Nullable ScheduledTask runOnEntity(Entity entity, Runnable task) {
		return wrap(entity.getScheduler().run(this.plugin, ignored -> task.run(), null));
	}

	@Override
	public @Nullable ScheduledTask runOnEntityDelayed(Entity entity, Runnable task, long delayTicks) {
		// Folia refuses a delay of 0 or less
		if (delayTicks <= 0) return this.runOnEntity(entity, task);
		return wrap(entity.getScheduler().runDelayed(this.plugin, ignored -> task.run(), null, delayTicks));
	}

	@Override
	public @Nullable ScheduledTask runOnEntityTimer(Entity entity, Runnable task, @Nullable Runnable retired,
																					   long delayTicks, long periodTicks) {
		return wrap(entity.getScheduler().runAtFixedRate(this.plugin, ignored -> task.run(), retired, Math.max(1, delayTicks), periodTicks));
	}

	@Override
	public @Nullable ScheduledTask runAtLocation(Location location, Runnable task) {
		return wrap(this.plugin.getServer().getRegionScheduler().run(this.plugin, location, ignored -> task.run()));
	}

	@Override
	public @Nullable ScheduledTask runAsyncDelayed(Runnable task, long delay, TimeUnit unit) {
		return wrap(this.plugin.getServer().getAsyncScheduler().runDelayed(this.plugin, ignored -> task.run(), Math.max(1, delay), unit));
	}
}
