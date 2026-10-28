package me.ronaldi2001.modutils.platform;

/** A task a {@link ModScheduler} started, so it can be cancelled. */
public interface ScheduledTask {
	void cancel();
}
