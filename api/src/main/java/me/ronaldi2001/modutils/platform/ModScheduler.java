package me.ronaldi2001.modutils.platform;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Runs code where the server wants it run. On Folia that is the thread that owns the entity or the region; on Paper and
 * Bukkit it is the main thread. Code written against this is the same on all of them.
 */
public interface ModScheduler {
	/** Runs on the thread of the entity, as soon as possible. */
	@Nullable ScheduledTask runOnEntity(Entity entity, Runnable task);

	/** Runs on the thread of the entity after {@code delayTicks}. */
	@Nullable ScheduledTask runOnEntityDelayed(Entity entity, Runnable task, long delayTicks);

	/**
	 * Runs on the thread of the entity every {@code periodTicks}, until cancelled or until the entity is gone
	 * ({@code retired} runs then, if given).
	 */
	@Nullable ScheduledTask runOnEntityTimer(Entity entity, Runnable task, @Nullable Runnable retired, long delayTicks, long periodTicks);

	/** Runs on the thread of the region that holds the location. */
	@Nullable ScheduledTask runAtLocation(Location location, Runnable task);

	/** Runs off the main thread after the delay. */
	@Nullable ScheduledTask runAsyncDelayed(Runnable task, long delay, TimeUnit unit);
}
